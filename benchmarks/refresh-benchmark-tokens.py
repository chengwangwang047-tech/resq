#!/usr/bin/env python3
"""Refresh JWTs through normal login, for private DAY24 test accounts only."""
import argparse, json, os, stat, urllib.request, urllib.error
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--accounts',required=True)
parser.add_argument('--tokens',required=True)
args=parser.parse_args()
accounts=Path(args.accounts).resolve(strict=True)
tokens=Path(args.tokens).resolve()
if tokens==accounts:
    raise SystemExit('Token output must differ from the accounts file.')
if accounts.stat().st_uid!=os.getuid() or stat.S_IMODE(accounts.stat().st_mode)&0o077:
    raise SystemExit('Accounts file must be owned by the current user and private (0600).')
if tokens.parent.stat().st_uid!=os.getuid() or stat.S_IMODE(tokens.parent.stat().st_mode)&0o077:
    raise SystemExit('Token destination directory must be owned by the current user and private (0700).')
if tokens.exists() and (tokens.stat().st_uid!=os.getuid() or stat.S_IMODE(tokens.stat().st_mode)&0o077):
    raise SystemExit('Existing token file must be owned by the current user and private (0600).')
credentials=json.loads(accounts.read_text())
if not credentials or any(not c.get('username','').startswith('d24_') for c in credentials):
    raise SystemExit('This helper accepts only explicitly named d24_ experiment accounts.')
result=[]
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*unused):
        return None
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
for c in credentials:
    data=json.dumps({'username':c['username'],'password':c['password']}).encode()
    req=urllib.request.Request('http://127.0.0.1:8080/api/auth/login',data=data,
                               headers={'Content-Type':'application/json'},method='POST')
    try:
        with opener.open(req,timeout=10) as response:body=json.load(response)
    except (urllib.error.URLError, ValueError):
        raise SystemExit('A normal test-user login failed; no new tokens saved.') from None
    result.append({'userId':c['userId'],'token':body['token']})
fd=os.open(tokens,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as handle:json.dump(result,handle)
os.chmod(tokens,0o600)
print('Refreshed private JWTs for '+str(len(result))+' test users. No token values displayed.')
