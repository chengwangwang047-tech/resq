#!/usr/bin/env python3
import unittest
from unittest.mock import patch
import practice


class PracticeSafetyTest(unittest.TestCase):
    def test_original_deploy_containers_are_refused_before_mutation(self):
        metadata={'guard':'a'*32}
        with patch.object(practice,'command',side_effect=['1'*64,'']) as command:
            with self.assertRaises(RuntimeError):practice.validate_existing(metadata)
            self.assertEqual(2,command.call_count)
            self.assertEqual('inspect',command.call_args.args[0][1])

    def test_matching_practice_containers_are_accepted(self):
        metadata={'guard':'a'*32}
        with patch.object(practice,'command',side_effect=['1'*64,'a'*32]):practice.validate_existing(metadata)

    def test_no_existing_containers_allows_recreation_from_own_volumes(self):
        with patch.object(practice,'command',return_value=''):practice.validate_existing({'guard':'a'*32})

    def test_inherited_real_connection_settings_are_removed(self):
        with patch.object(practice.os,'environ',{'PATH':'synthetic','RESQ_PROD_JWT_SECRET_BASE64':'SYNTHETIC','RESQ_DB_PASSWORD':'SYNTHETIC','COMPOSE_FILE':'SYNTHETIC'}):
            self.assertEqual({'PATH':'synthetic','MAVEN_SKIP_RC':'true'},practice.environment())


if __name__=='__main__':unittest.main()
