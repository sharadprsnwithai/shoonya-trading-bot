import os
import sys
import json
import time
import requests
import hashlib
import hmac
import base64
import struct
from datetime import datetime, timedelta
import pandas as pd

def load_env():
    env = {}
    if os.path.exists('.env'):
        with open('.env', 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    k, v = line.split('=', 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
    return env

def generate_totp(secret):
    k = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    counter = int(time.time()) // 30
    h = hmac.new(k, struct.pack(">Q", counter), hashlib.sha1).digest()
    off = h[19] & 0xF
    totp = str((struct.unpack(">I", h[off : off + 4])[0] & 0x7FFFFFFF) % 10**6).zfill(6)
    return totp

def derive_shoonya_app_key(user_id):
    offsets = [83, 50, 97, 114, 110, 46, 27, 93]
    sb = user_id + "|"
    for p, o in enumerate(offsets):
        sb += chr(o + p)
    return hashlib.sha256(sb.encode('utf-8')).hexdigest()

def get_public_ip():
    try:
        r = requests.get('https://api.ipify.org', timeout=5)
        return r.text.strip()
    except:
        return '127.0.0.1'

def authenticate_shoonya(env):
    base_url = env.get('SHOONYA_BASE_URL', 'https://api.shoonya.com').rstrip('/')
    uid = env.get('SHOONYA_USER_ID', '')
    pwd = env.get('SHOONYA_PASSWORD', '')
    totp_secret = env.get('SHOONYA_TOTP_SECRET', '')
    client_id = env.get('SHOONYA_CLIENT_ID', uid + '_U')
    vendor_code = env.get('SHOONYA_VENDOR_CODE', 'NOREN_API')
    secret_key = env.get('SHOONYA_SECRET_KEY', '')
    public_ip = env.get('SHOONYA_PUBLIC_IP', '') or get_public_ip()
    
    totp = generate_totp(totp_secret)
    pwd_sha = hashlib.sha256(pwd.encode('utf-8')).hexdigest()
    app_key = derive_shoonya_app_key(uid)
    
    payload = {
        "apkversion": "W2_20250926",
        "uid": uid,
        "pwd": pwd_sha,
        "factor2": totp,
        "appkey": app_key,
        "imei": "12345678-1234-1234-1234-123456789abc",
        "addldivinf": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "source": "API",
        "vc": vendor_code,
        "app_key": client_id
    }
    
    url = f"{base_url}/NorenWClientAPI/QuickAuth"
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Origin": "https://api.shoonya.com",
        "Referer": f"https://api.shoonya.com/OAuthlogin/authorize/oauth?client_id={client_id}",
        "X-Forwarded-For": public_ip
    }
    body = "jData=" + json.dumps(payload)
    
    resp = requests.post(url, data=body, headers=headers, timeout=15)
    data = resp.json()
    if data.get('stat') == 'Ok' and 'authcode' in data:
        auth_code = data['authcode']
        # Step 2: GenAcsTok
        checksum = hashlib.sha256((client_id + secret_key + auth_code).encode('utf-8')).hexdigest()
        gen_acs_payload = {
            "client_id": client_id,
            "code": auth_code,
            "checksum": checksum
        }
        gen_acs_url = f"{base_url}/NorenWClientAPI/GenAcsTok"
        gen_acs_body = "jData=" + json.dumps(gen_acs_payload)
        gen_acs_resp = requests.post(gen_acs_url, data=gen_acs_body, headers=headers, timeout=15)
        gen_acs_json = gen_acs_resp.json()
        
        token = gen_acs_json.get('susertoken') or gen_acs_json.get('access_token')
        if token:
            print(f"[AUTH SUCCESS] Logged into Shoonya successfully! User: {uid}")
            return base_url, uid, token, public_ip
        else:
            print(f"[AUTH ERROR] GenAcsTok failed: {gen_acs_json}")
            return None, None, None, None
    elif data.get('stat') == 'Ok' and 'susertoken' in data:
        return base_url, uid, data['susertoken'], public_ip
    else:
        print(f"[AUTH ERROR] QuickAuth failed: {data}")
        return None, None, None, None

def search_silver_contracts(base_url, uid, token, public_ip):
    payload = {
        "uid": uid,
        "exch": "MCX",
        "stext": "SILVER"
    }
    url = f"{base_url}/NorenWClientAPI/SearchScrip"
    headers = {"Content-Type": "application/x-www-form-urlencoded", "X-Forwarded-For": public_ip}
    body = "jData=" + json.dumps(payload) + f"&jKey={token}"
    resp = requests.post(url, data=body, headers=headers, timeout=15)
    data = resp.json()
    if data.get('stat') == 'Ok' and 'values' in data:
        print(f"\nFound {len(data['values'])} MCX SILVER scrips:")
        for v in data['values']:
            if 'FUTCOM' in v.get('instname', '') or 'SILVER' in v.get('tsym', ''):
                print(f"  • Token: {v.get('token')} | Tsym: {v.get('tsym')} | Inst: {v.get('instname')} | Expiry: {v.get('exd')}")
        return data['values']
    else:
        print(f"SearchScrip response: {data}")
    return []

if __name__ == '__main__':
    env = load_env()
    base_url, uid, token, ip = authenticate_shoonya(env)
    if token:
        search_silver_contracts(base_url, uid, token, ip)
