"""Shared JSON HTTP transport; never logs headers, credentials, tokens or response bodies."""
import json
import urllib.error
import urllib.request


class TransportError(RuntimeError):pass


def open_direct(request,timeout=15):
    # All current callers target isolated loopback services; never route through a host proxy.
    return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=timeout)


def request_json(base_url, method, path, payload=None, token=None, request_id=None, timeout=15):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if request_id: headers['X-Request-Id']=request_id
    data=None if payload is None else json.dumps(payload).encode()
    if data is not None: headers['Content-Type']='application/json'
    req=urllib.request.Request(base_url+path,data=data,headers=headers,method=method)
    try: response=open_direct(req,timeout=timeout)
    except urllib.error.HTTPError as error: response=error
    except urllib.error.URLError as error:
        raise TransportError(method+' '+path+' transport failure ('+type(error.reason).__name__+')') from None
    except TimeoutError:
        raise TransportError(method+' '+path+' transport timeout') from None
    with response:
        status,body=response.status,response.read()
    return status,json.loads(body) if body else None
