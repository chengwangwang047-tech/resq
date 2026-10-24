#!/usr/bin/env python3
"""Offline demo boundary tests: no Docker, Maven, HTTP or real credentials."""
from pathlib import Path
import contextlib
import io
import json
import tempfile
import unittest
from unittest.mock import patch,MagicMock
import common as c
import prepare
import run as runner


def state():
    guard='a'*32
    return {'guard':guard,'project':'resq-demo-'+guard[:12],'version':'test','createdAtUtc':'synthetic',
            'commit':'synthetic','jarSha256':'synthetic','timeoutSeconds':60,'ports':{},'ready':True,
            'modules':{},'image':'resq:synthetic','phase':'ready'}


class DemoSafetyTest(unittest.TestCase):
    def test_invalid_session_names_cannot_escape_root(self):
        for identity in ('../../deploy','deploy','a'*31,None):
            with self.subTest(identity=identity),self.assertRaises(c.DemoError):c.session_path(identity)

    def test_symlink_session_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/('a'*32)).symlink_to(root,target_is_directory=True)
            with patch.object(c,'ROOT',root),self.assertRaises(c.DemoError):c.session_path('a'*32)

    def test_default_deploy_project_is_refused_before_inventory(self):
        value=state();value['project']='deploy'
        with patch.object(c,'inventory') as inventory,self.assertRaises(c.DemoError):c.verify_all(value)
        inventory.assert_not_called()

    def test_cleanup_checks_all_ownership_before_any_removal(self):
        value=state()
        resources={'container':['1'*64],'network':['2'*64],'volume':['foreign_data']}
        with patch.object(c,'inventory',return_value=resources),patch.object(c,'verify_resource',side_effect=[None,None,c.DemoError('mismatch')]),patch.object(c,'invoke') as command:
            with self.assertRaises(c.DemoError):c.cleanup(value,Path('/synthetic'))
            command.assert_not_called()

    def test_unlabelled_existing_volume_name_prevents_prepare(self):
        import subprocess
        with patch.object(c,'inventory',return_value={'container':[],'network':[],'volume':[]}),patch.object(c.subprocess,'run',return_value=subprocess.CompletedProcess([],0)) as command:
            with self.assertRaises(c.DemoError):c.require_fresh_names(state())
            self.assertEqual('volume',command.call_args.args[0][1])

    def test_short_network_id_is_verified_by_actual_name_and_labels(self):
        value=state()
        with patch.object(c,'invoke',side_effect=[value['project']+'|'+value['guard'],value['project']+'_resq_net']):
            c.verify_resource('network','abc123def456',value)

    def test_foreign_network_name_is_rejected_despite_matching_labels(self):
        value=state()
        with patch.object(c,'invoke',side_effect=[value['project']+'|'+value['guard'],'deploy_resq_net']),self.assertRaises(c.DemoError):
            c.verify_resource('network','abc123def456',value)

    def test_redis_destructive_and_unscoped_commands_are_refused(self):
        for args in (('FLUSHALL',),('FLUSHDB',),('DEL','resource:1'),('GET','*'),('GET','rate:*')):
            with self.subTest(args=args),patch.object(c,'invoke') as command,self.assertRaises(c.DemoError):
                c.redis(state(),Path('/synthetic'),*args)
            command.assert_not_called()

    def test_sql_requires_verified_container_before_access(self):
        with patch.object(c,'container',side_effect=c.DemoError('wrong owner')),patch.object(c.seeds,'execute_sql') as sql:
            with self.assertRaises(c.DemoError):c.sql(state(),Path('/synthetic'),'SELECT 1;')
            sql.assert_not_called()

    def test_config_tampering_refuses_before_docker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);directory=root/('a'*32);directory.mkdir()
            value=state();value['envHash']='different'
            (directory/'state.json').write_text(json.dumps(value));(directory/'synthetic.env').write_text('synthetic')
            with patch.object(c,'ROOT',root),patch.object(c,'invoke') as command,self.assertRaises(c.DemoError):c.load('a'*32)
            command.assert_not_called()

    def test_cleaned_session_status_does_not_need_deleted_secret_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);directory=root/('a'*32);directory.mkdir()
            value=state();value['cleaned']=True
            (directory/'state.json').write_text(json.dumps(value))
            with patch.object(c,'ROOT',root):self.assertTrue(c.load('a'*32)[0]['cleaned'])

    def test_already_attempted_module_is_not_silently_replayed(self):
        value=state();value['modules']['basic']={'status':'FAILED','checks':[]}
        with patch.object(c,'verify_all'),patch.object(c,'container',return_value='1'*64),patch.object(c,'invoke',return_value='healthy'),patch.dict(runner.MODULES,{'basic':MagicMock()}) as modules:
            with self.assertRaises(c.DemoError):runner.run_modules(value,Path('/synthetic'),['basic'])
            modules['basic'].assert_not_called()

    def test_report_failure_keeps_failed_status_and_raises(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=state()
            with patch.object(c,'verify_all'),patch.object(c,'container',return_value='1'*64),patch.object(c,'invoke',return_value='healthy'),patch.dict(runner.MODULES,{'basic':MagicMock(side_effect=c.DemoError('synthetic assertion failed'))}):
                with self.assertRaises(c.DemoError):runner.run_modules(value,Path(temporary),['basic'])
            report=json.loads((Path(temporary)/'report.json').read_text())
            self.assertEqual('FAILED',report['modules']['basic']['status'])

    def test_reports_exclude_private_top_level_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=state();value.update(password='SYNTHETIC-PRIVATE',token='SYNTHETIC-TOKEN',credentials={'password':'SYNTHETIC-PRIVATE'})
            c.save_report(value,Path(temporary))
            text=(Path(temporary)/'report.json').read_text()+(Path(temporary)/'report.md').read_text()
            self.assertNotIn('SYNTHETIC-PRIVATE',text);self.assertNotIn('SYNTHETIC-TOKEN',text)

    def test_invalid_timeout_does_not_create_environment(self):
        with patch.object(c,'session_path') as path,self.assertRaises(c.DemoError):prepare.prepare(1)
        path.assert_not_called()

    def test_build_timeout_only_terminates_its_own_process_group(self):
        import subprocess,signal
        process=MagicMock(pid=43210)
        process.wait.side_effect=subprocess.TimeoutExpired('synthetic-maven',300)
        process.poll.return_value=-15
        with tempfile.TemporaryDirectory() as temporary,patch.object(c.subprocess,'Popen',return_value=process) as start,patch.object(c.os,'killpg') as kill:
            with self.assertRaises(subprocess.TimeoutExpired):c.run_logged(['synthetic-maven'],Path(temporary)/'build.log',300)
            self.assertTrue(start.call_args.kwargs['start_new_session'])
            kill.assert_called_once_with(43210,signal.SIGTERM)

    def test_shared_transport_handles_401_without_printing_token(self):
        import http_probe
        from urllib.error import HTTPError
        error=HTTPError('http://synthetic',401,'synthetic',{},io.BytesIO(b'{"message":"unauthorized"}'))
        with patch.object(http_probe,'open_direct',side_effect=error),contextlib.redirect_stdout(io.StringIO()) as output:
            status,body=http_probe.request_json('http://synthetic','GET','/test',token='SYNTHETIC-TOKEN')
        self.assertEqual(401,status);self.assertEqual('unauthorized',body['message']);self.assertEqual('',output.getvalue())

    def test_loopback_transport_ignores_inherited_proxy_configuration(self):
        import http_probe
        opener=MagicMock()
        with patch.object(http_probe.urllib.request,'build_opener',return_value=opener) as build:
            http_probe.open_direct(MagicMock(),timeout=2)
        self.assertEqual({},build.call_args.args[0].proxies)

    def test_transport_errors_only_expose_reason_type_not_private_message(self):
        import http_probe
        from urllib.error import URLError
        with patch.object(http_probe,'open_direct',side_effect=URLError('SYNTHETIC-PRIVATE-DETAIL')):
            with self.assertRaises(http_probe.TransportError) as result:http_probe.request_json('http://synthetic','POST','/api/auth/login',payload={'password':'SYNTHETIC-PRIVATE-PASSWORD'})
            self.assertNotIn('SYNTHETIC-PRIVATE',str(result.exception))

    def test_subprocess_transport_errors_do_not_dump_arguments_or_stderr(self):
        import subprocess
        error=subprocess.CalledProcessError(1,['SYNTHETIC-PRIVATE-ARG'],stderr='SYNTHETIC-PRIVATE-STDERR')
        with patch.object(c.deployment,'run',side_effect=error):
            with self.assertRaises(c.DemoError) as result:c.invoke(['synthetic'],'Safe stage')
            self.assertNotIn('SYNTHETIC-PRIVATE',str(result.exception))


if __name__=='__main__':unittest.main()
