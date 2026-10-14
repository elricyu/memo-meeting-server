// Test-only browser cookie/CSRF harness; uses isolated test users.
import { randomBytes } from 'node:crypto'
export class Session {
  constructor(base) { this.base=base;this.cookies=new Map();this.csrf='' }
  cookie() { return [...this.cookies].map(([name,value])=>`${name}=${value}`).join('; ') }
  async api(path,method='GET',body,extra={}) {
    if(method!=='GET' && !extra.Authorization && !this.csrf) this.csrf=(await this.api('/auth/csrf')).body.token
    const headers={'Content-Type':'application/json',Cookie:this.cookie(),...(method!=='GET' && !extra.Authorization?{'X-XSRF-TOKEN':this.csrf}:{}),...extra}
    const response=await fetch(`${this.base}/api/v1${path}`,{method,headers,...(body?{body:JSON.stringify(body)}:{})})
    for(const cookie of response.headers.getSetCookie()) {const pair=cookie.split(';')[0], split=pair.indexOf('=');this.cookies.set(pair.slice(0,split),pair.slice(split+1))}
    const result=await response.json().catch(()=>({}))
    return {status:response.status,body:result}
  }
  async register(prefix='test') { const username=`${prefix}_${randomBytes(7).toString('hex')}`;const result=await this.api('/auth/register','POST',{username,displayName:`${prefix}测试用户`,password:'explicit-test-password'});if(result.status!==200)throw new Error(`Register failed ${result.status}: ${JSON.stringify(result.body)}`);this.user=result.body.user;this.username=username;return this.user }
}
export async function native(base,path,method='GET',body,token) {
  const response=await fetch(`${base}/api/v1${path}`,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})})
  return {status:response.status,body:await response.json().catch(()=>({}))}
}
