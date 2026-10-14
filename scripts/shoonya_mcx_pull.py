"""Pull real MCX historical candles from Shoonya (Finvasia NorenAPI).

Delivers real MCX silver 5m history for the silver-ORB strategy — removing the
yfinance SI=F proxy and yfinance's hard 60-day 5m cap.

Auth mirrors the repo's Java implementation exactly (ShoonyaAuthenticator +
CryptoUtil):
  appkey    = sha256(f"{uid}|" + chars from per-position offsets)
  QuickAuth = jData{apkversion, uid, pwd=sha256(password), factor2=TOTP,
              appkey, imei, addldivinf, source, vc, app_key=client_id} -> code
  GenAcsTok = jData{client_id, code, checksum=sha256(client_id+secret+code)}
              -> susertoken
  API calls = form body "jData={json}&jKey=<susertoken>",
              header X-Forwarded-For = public IP

Session reuse: reads/writes data/shoonya_session.json — the SAME file the Java
bot uses (12h freshness), so bot and this script share one login.

Multi-contract stitching: MCX silver futures expire Feb/Apr/May/Jun/Aug/Oct/Dec
(20th of the month), so multi-month history spans several contracts. Each
calendar day maps to the contract with the nearest expiry >= that day (roll at
day boundary only). Every ORB/VWAP trade is intraday, so roll gaps cannot
distort VWAP, the opening range, or any trade's P&L.

Usage:
  python scripts/shoonya_mcx_pull.py --discover
      List MCX silver contracts (tsym/token/expiry) available to this account.
  python scripts/shoonya_mcx_pull.py --days 120
      Pull & stitch to data/mcx_silver_5m_120d.csv (auto contract selection).
  python scripts/shoonya_mcx_pull.py --days 120 \\
      --contract SILVER27OCT26FUT:69123 --contract SILVER27AUG26FUT:69100
      Force specific contract(s) (needed when an expired contract is not
      returned by SearchScrip).
  python scripts/shoonya_mcx_pull.py --days 95 --token 495214
      Single-token pull for the full range, bypassing SearchScrip — the mode to
      use while the account's MCX segment is not enabled for search
      (495214 = SILVER04DEC26 as of 2026-10).

Stdlib only — no pip installs required.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / ".env"
SESSION_FILE = ROOT / "data" / "shoonya_session.json"

IST = timezone(timedelta(hours=5, minutes=30))
UTC = timezone.utc

APK_VERSION = "W2_20250926"
IMEI = "12345678-1234-1234-1234-123456789abc"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
# Per-position char offsets used by CryptoUtil.deriveShoonyaAppKey (Java).
KEY_OFFSETS = (83, 50, 97, 114, 110, 46, 27, 93)

MONTHS = (
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)
MONTH_RE = r"(\d{1,2})?(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(\d{2}|\d{4})"

SESSION_MARKERS = (
    "Session Expired", "Invalid Session Key", "NOT_LOGGED_IN",
    "Invalid Token", "INVALID_SESSION",
)
RATE_MARKER = "exceeds Limit"

CHUNK_DAYS = 10  # TPSeries safety: ~175 5m bars/day * 10d << typical 3000 cap
SLEEP_BETWEEN_CALLS = 0.45  # stay well inside Noren's 10-req/sec throttle


class PullError(RuntimeError):
    """Non-retryable API/config failure with a safe (secret-free) message."""


# --------------------------------------------------------------------------
# .env + crypto helpers
# --------------------------------------------------------------------------
def load_env() -> dict[str, str]:
    """Parse the repo .env into a dict (no shell expansion; quotes stripped)."""
    env: dict[str, str] = {}
    if not ENV_PATH.exists():
        return env
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def sha256(text: str) -> str:
    """Lowercase hex SHA-256 (matches CryptoUtil.sha256)."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def derive_appkey(uid: str) -> str:
    """Shoonya proprietary appkey derivation (mirrors CryptoUtil)."""
    suffix = "".join(chr(offset + i) for i, offset in enumerate(KEY_OFFSETS))
    return sha256(f"{uid}|{suffix}")


def generate_totp(secret: str) -> str | None:
    """RFC-6238 6-digit TOTP; returns a raw 6-digit code as-is (Java parity)."""
    secret = (secret or "").strip().replace(" ", "")
    if not secret:
        return None
    if re.fullmatch(r"\d{6}", secret):
        return secret
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8), casefold=True)
    counter = int(time.time()) // 30
    digest = hmac.new(key, counter.to_bytes(8, "big"), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = int.from_bytes(digest[offset:offset + 4], "big") & 0x7FFFFFFF
    return f"{code % 10 ** 6:06d}"


def resolve_public_ip(configured: str) -> str:
    """Public IP: .env SHOONYA_PUBLIC_IP first, else api.ipify.org (Java parity)."""
    if configured and configured.strip():
        return configured.strip()
    try:
        with urllib.request.urlopen("https://api.ipify.org", timeout=4) as resp:
            ip = resp.read().decode("utf-8").strip()
            if ip:
                return ip
    except Exception:
        pass
    return ""


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------
def post_form(url: str, body: str, headers: dict[str, str],
              timeout: int = 20) -> tuple[int, str]:
    """POST a raw form body; returns (status, body).

    Note: Noren endpoints do NOT URL-decode jData — Java's buildFormBody sends
    the JSON raw (only '&' escaped), so we mirror that instead of urlencode().
    """
    data = body.encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def build_body(jdata: dict, token: str | None = None) -> str:
    """Raw 'jData={json}[&jKey=token]' body, matching Java buildFormBody."""
    payload = json.dumps(jdata, separators=(",", ":")).replace("&", "%26")
    body = f"jData={payload}"
    if token is not None:
        body += f"&jKey={token}"
    return body


# --------------------------------------------------------------------------
# Shoonya client
# --------------------------------------------------------------------------
class Shoonya:
    """Minimal NorenAPI client: session cache + QuickAuth/GenAcsTok + calls."""

    def __init__(self, env: dict[str, str]):
        for required in ("SHOONYA_USER_ID", "SHOONYA_PASSWORD"):
            if not env.get(required):
                raise PullError(f"Missing {required} in .env")
        self.uid = env["SHOONYA_USER_ID"]
        self.password = env["SHOONYA_PASSWORD"]
        self.totp_secret = env.get("SHOONYA_TOTP_SECRET", "")
        self.client_id = env.get("SHOONYA_CLIENT_ID", "")
        self.secret_key = env.get("SHOONYA_SECRET_KEY", "")
        self.vendor_code = env.get("SHOONYA_VENDOR_CODE", "NOREN_API")
        self.base = env.get("SHOONYA_BASE_URL", "https://api.shoonya.com").rstrip("/")
        self.ip = resolve_public_ip(env.get("SHOONYA_PUBLIC_IP", ""))
        self.token: str | None = None

    # ---------------- session ----------------
    def _load_disk_session(self) -> str | None:
        if not SESSION_FILE.exists():
            return None
        try:
            data = json.loads(SESSION_FILE.read_text(encoding="utf-8"))
            token = data.get("accessToken")
            created = data.get("createdAt")
            if (token and data.get("userId") == self.uid and created
                    and datetime.now(UTC) < datetime.fromisoformat(created.replace("Z", "+00:00")) + timedelta(hours=12)):
                return token
        except Exception:
            return None
        return None

    def _save_disk_session(self, token: str) -> None:
        SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)
        SESSION_FILE.write_text(json.dumps({
            "accessToken": token,
            "userId": self.uid,
            "createdAt": datetime.now(UTC).isoformat(),
        }), encoding="utf-8")

    def _validate(self, token: str) -> bool:
        status, body = post_form(
            f"{self.base}/NorenWClientAPI/UserDetails",
            build_body({"uid": self.uid}, token),
            {"Content-Type": "application/x-www-form-urlencoded",
             "X-Forwarded-For": self.ip},
            timeout=10,
        )
        if status != 200:
            return False
        try:
            return str(json.loads(body).get("stat", "")).lower() == "ok"
        except Exception:
            return False

    def _login(self) -> str:
        """QuickAuth -> GenAcsTok (exact Java flow)."""
        pwd_sha = sha256(self.password)
        totp = generate_totp(self.totp_secret)
        appkey = derive_appkey(self.uid)
        quick_payload = {
            "apkversion": APK_VERSION, "uid": self.uid, "pwd": pwd_sha,
            "factor2": totp, "appkey": appkey, "imei": IMEI,
            "addldivinf": USER_AGENT, "source": "API",
            "vc": self.vendor_code, "app_key": self.client_id,
        }
        headers = {
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": "https://api.shoonya.com",
            "Referer": f"https://api.shoonya.com/OAuthlogin/authorize/oauth?client_id={self.client_id}",
            "X-Forwarded-For": self.ip,
        }
        status, body = post_form(f"{self.base}/NorenWClientAPI/QuickAuth",
                                 build_body(quick_payload), headers, timeout=15)
        try:
            data = json.loads(body)
        except Exception:
            raise PullError(f"QuickAuth non-JSON response (HTTP {status})")
        if str(data.get("stat", "")).lower() != "ok":
            msg = str(data.get("emsg", body))[:200]
            if "IP" in msg.upper() or "WHITELIST" in msg.upper():
                msg += " [Whitelist this machine's public IP in Prism: https://prism.finvasia.com/]"
            raise PullError(f"QuickAuth failed: {msg}")

        token = None
        code = data.get("code")
        if code:
            checksum = sha256(self.client_id + self.secret_key + code)
            gen_payload = {"client_id": self.client_id, "code": code, "checksum": checksum}
            status, body = post_form(f"{self.base}/NorenWClientAPI/GenAcsTok",
                                     build_body(gen_payload),
                                     {"Content-Type": "application/x-www-form-urlencoded",
                                      "X-Forwarded-For": self.ip},
                                     timeout=15)
            try:
                gen = json.loads(body)
            except Exception:
                raise PullError(f"GenAcsTok non-JSON response (HTTP {status})")
            if str(gen.get("stat", "")).lower() != "ok":
                msg = str(gen.get("emsg", body))[:300]
                if "INVALID_IP" in msg:
                    msg += (" [Finvasia requires whitelisting this machine's public IP "
                            f"({self.ip}) in Prism: https://prism.finvasia.com/] ")
                raise PullError(f"GenAcsTok failed: {msg}")
            token = gen.get("susertoken") or gen.get("access_token")
        # QuickAuth's own susertoken is NOT a valid API jKey — only the
        # GenAcsTok exchange token is (Java never falls back to it either).
        if not token:
            raise PullError("Login returned no GenAcsTok code/token")
        self.token = token
        self._save_disk_session(token)
        return token

    def get_token(self) -> str:
        if self.token:
            return self.token
        cached = self._load_disk_session()
        if cached and self._validate(cached):
            self.token = cached
            return cached
        return self._login()

    # ---------------- API calls ----------------
    def call(self, path: str, jdata: dict, retries: int = 3):
        """POST a Noren endpoint; auto re-login on session expiry, back off on throttle."""
        last_error = ""
        for attempt in range(retries):
            token = self.get_token()
            status, body = post_form(
                f"{self.base}{path}",
                build_body(jdata, token),
                {"Content-Type": "application/x-www-form-urlencoded",
                 "X-Forwarded-For": self.ip},
                timeout=30,
            )
            if any(marker in body for marker in SESSION_MARKERS) or status in (401, 403):
                self.token = None
                try:
                    SESSION_FILE.unlink(missing_ok=True)
                except Exception:
                    pass
                last_error = "session expired (re-authenticating)"
                time.sleep(1.0)
                continue
            if RATE_MARKER in body or status == 400 and "Limit" in body:
                time.sleep(1.2)
                last_error = "rate limited"
                continue
            try:
                return json.loads(body)
            except Exception:
                raise PullError(f"{path}: non-JSON response (HTTP {status})")
        raise PullError(f"{path}: gave up after {retries} attempts ({last_error})")

    # ---------------- endpoints ----------------
    def search_scrip(self, exch: str, text: str) -> list[dict]:
        for stext in (text, f"%{text}%"):
            data = self.call("/NorenWClientAPI/SearchScrip",
                             {"uid": self.uid, "exch": exch, "stext": stext})
            if str(data.get("stat", "")).lower() == "ok":
                values = data.get("values") or []
                if values:
                    return values
            elif data.get("emsg"):
                print(f"  SearchScrip {exch} '{stext}': {data['emsg']}", flush=True)
            time.sleep(SLEEP_BETWEEN_CALLS)
        return []

    def tpseries(self, exch: str, token: str, start: datetime, end: datetime,
                 interval: str = "5") -> list[dict]:
        data = self.call("/NorenWClientAPI/TPSeries", {
            "uid": self.uid, "exch": exch, "token": str(token),
            "st": str(int(start.timestamp())), "et": str(int(end.timestamp())),
            "intrv": interval,
        })
        if isinstance(data, list):
            return data
        if str(data.get("stat", "")).lower() != "ok":
            raise PullError(f"TPSeries {token}: {str(data.get('emsg', data))[:200]}")
        return []


# --------------------------------------------------------------------------
# Bar parsing / contract discovery
# --------------------------------------------------------------------------
def parse_bar_time(node: dict) -> datetime | None:
    """Candle timestamp in IST: prefer 'time' string, else ssboe epoch (Java parity)."""
    raw = str(node.get("time") or "").strip()
    if raw:
        for fmt in ("%d-%m-%Y %H:%M:%S", "%d/%m/%Y %H:%M:%S",
                    "%d-%m-%Y %H:%M", "%d/%m/%Y %H:%M"):
            try:
                return datetime.strptime(raw, fmt).replace(tzinfo=IST)
            except ValueError:
                pass
        try:
            parsed = datetime.fromisoformat(raw)
            return parsed if parsed.tzinfo else parsed.replace(tzinfo=IST)
        except ValueError:
            pass
    try:
        epoch = int(node.get("ssboe") or 0)
    except (TypeError, ValueError):
        epoch = 0
    if epoch > 0:
        return datetime.fromtimestamp(epoch, tz=UTC).astimezone(IST)
    return None


def parse_bars(raw: list[dict]) -> list[tuple[datetime, float, float, float, float, int]]:
    """Normalize TPSeries rows -> (ist_time, o, h, l, c, v); drops invalid rows."""
    now = datetime.now(IST) + timedelta(minutes=5)
    bars = []
    for node in raw:
        if not isinstance(node, dict) or "into" not in node:
            continue
        try:
            o, h, l, c = (float(node[k]) for k in ("into", "inth", "intl", "intc"))
            v = int(float(node.get("intv") or node.get("v") or 0))
        except (TypeError, ValueError):
            continue
        if min(o, h, l, c) <= 0:
            continue
        ts = parse_bar_time(node)
        if ts is None or ts > now:
            continue
        bars.append((ts, o, h, l, c, abs(v)))
    return bars


def parse_expiry(tsym: str) -> date | None:
    """Extract the delivery expiry date embedded in an MCX futures tsym."""
    match = re.search(MONTH_RE, tsym.upper())
    if not match:
        return None
    day = int(match.group(1)) if match.group(1) else 20  # MCX metals default 20th
    month = MONTHS.index(match.group(2)) + 1
    year = int(match.group(3)) if len(match.group(3)) == 4 else 2000 + int(match.group(3))
    try:
        return date(year, month, day)
    except ValueError:
        return None


def is_silver_future(row: dict) -> bool:
    """Main MCX SILVER future (excludes mini/tick/spot/options)."""
    tsym = str(row.get("tsym", "")).upper()
    if not tsym.startswith("SILVER"):
        return False
    if tsym.startswith(("SILVERM", "SILVERT")):  # SILVERM mini / SILVERTICK
        return False
    return "FUT" in tsym and parse_expiry(tsym) is not None


# --------------------------------------------------------------------------
# Planning / stitching
# --------------------------------------------------------------------------
def plan_segments(contracts: list[dict], start: date, end: date) -> list[dict]:
    """Map each calendar day to the nearest-expiry contract still alive that day."""
    ordered = sorted(contracts, key=lambda c: c["expiry"])
    segments: list[dict] = []
    current: dict | None = None
    day = start
    while day <= end:
        candidates = [c for c in ordered if c["expiry"] >= day]
        contract = min(candidates, key=lambda c: c["expiry"]) if candidates else None
        if contract is None:
            raise PullError(
                f"No discovered contract covers {day}; pass --contract TSYM:TOKEN "
                "(expired contracts are sometimes missing from SearchScrip)."
            )
        if current is None or current["contract"]["tsym"] != contract["tsym"]:
            if current:
                segments.append(current)
            current = {"contract": contract, "start": day, "end": day}
        else:
            current["end"] = day
        day += timedelta(days=1)
    if current:
        segments.append(current)
    return segments


def fetch_segment(client: Shoonya, exch: str, seg: dict, interval: str) -> list[tuple]:
    """Fetch one contract's bars for [start..end] in CHUNK_DAYS windows."""
    contract = seg["contract"]
    bars: list[tuple] = []
    cursor = seg["start"]
    while cursor <= seg["end"]:
        chunk_end = min(cursor + timedelta(days=CHUNK_DAYS - 1), seg["end"])
        st = datetime.combine(cursor, datetime.min.time(), tzinfo=IST)
        et = datetime.combine(chunk_end + timedelta(days=1), datetime.min.time(), tzinfo=IST)
        rows = client.tpseries(exch, contract["token"], st, et, interval)
        bars.extend(parse_bars(rows))
        print(f"  {contract['tsym']}: {cursor}..{chunk_end} -> {len(rows)} rows "
              f"({len(bars)} total)", flush=True)
        time.sleep(SLEEP_BETWEEN_CALLS)
        cursor = chunk_end + timedelta(days=1)
    return bars


def coverage_report(bars: list[tuple], start: date, end: date) -> dict:
    """Trading-day coverage + session hour profile (validates the 18:00-18:30 ORB)."""
    by_day: dict[date, int] = {}
    for ts, *_ in bars:
        by_day[ts.date()] = by_day.get(ts.date(), 0) + 1
    expected = (end - start).days + 1
    calendar_days = [start + timedelta(days=i) for i in range(expected)]
    weekdays = [d for d in calendar_days if d.weekday() < 5]
    hours = sorted({ts.hour for ts, *_ in bars})
    mins = sorted({ts.minute for ts, *_ in bars if ts.hour == 18})
    orb_bars = sum(1 for ts, *_ in bars
                   if ts.hour == 18 and 0 <= ts.minute < 30)
    per_day = sorted(by_day.values())
    return {
        "trading_days": len(by_day),
        "weekday_expected": len(weekdays),
        "missing_weekdays": [d for d in weekdays if d not in by_day],
        "hours": hours,
        "first_hour": hours[0] if hours else None,
        "last_hour": hours[-1] if hours else None,
        "bars_1800_1830": orb_bars,
        "bars_18_minutes": mins,
        "median_bars_per_day": per_day[len(per_day) // 2] if per_day else 0,
        "min_bars_per_day": per_day[0] if per_day else 0,
    }


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="Pull real MCX candles from Shoonya for the silver-ORB backtest.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage:")[1] if "Usage:" in __doc__ else None,
    )
    parser.add_argument("--discover", action="store_true",
                        help="list MCX silver contracts (tsym/token/expiry) and exit")
    parser.add_argument("--days", type=int, default=90,
                        help="history depth in calendar days (default 90)")
    parser.add_argument("--interval", default="5", help="candle minutes (default 5)")
    parser.add_argument("--exch", default="MCX", help="exchange (default MCX)")
    parser.add_argument("--search", default="SILVER", help="SearchScrip text (default SILVER)")
    parser.add_argument("--contract", action="append", default=[], metavar="TSYM:TOKEN",
                        help="force a contract instead of auto selection (repeatable)")
    parser.add_argument("--token", default=None,
                        help="fetch a single token for the full range, bypassing "
                             "SearchScrip/expiry stitching (use while the MCX segment "
                             "is not enabled for search; e.g. 495214 = SILVER04DEC26)")
    parser.add_argument("--out", default=None, help="output CSV path")
    args = parser.parse_args()

    env = load_env()
    client = Shoonya(env)
    print(f"Shoonya puller | uid={client.uid} | base={client.base} | "
          f"ip={client.ip or '(unresolved)'}", flush=True)

    start = date.today() - timedelta(days=args.days - 1)
    end = date.today()

    # ---- contract selection ----
    contracts: list[dict] = []
    if args.token:
        contracts = [{"tsym": f"TOKEN-{args.token}", "token": args.token,
                      "expiry": date.max}]
        print(f"\nSingle-token mode: MCX {args.token} over {start}..{end} "
              "(SearchScrip bypassed).", flush=True)
    else:
        # ---- discovery ----
        print(f"Searching {args.exch} for '{args.search}' ...", flush=True)
        rows = client.search_scrip(args.exch, args.search)
        candidates = [r for r in rows if is_silver_future(r)]
        print(f"\nSearchScrip returned {len(rows)} scrips; {len(candidates)} look like "
              f"main-future SILVER contracts:\n")
        print(f"  {'TSYM':<26} {'TOKEN':<10} {'EXPIRY':<12} {'CNAME'}")
        for row in sorted(candidates, key=lambda r: str(r.get("tsym"))):
            tsym = str(row["tsym"])
            expiry = parse_expiry(tsym)
            print(f"  {tsym:<26} {str(row.get('token', '')):<10} "
                  f"{str(expiry or '?'):<12} {row.get('cname', '')}")

        if args.discover:
            return 0

        if args.contract:
            for spec in args.contract:
                if ":" not in spec:
                    raise PullError(f"--contract expects TSYM:TOKEN, got '{spec}'")
                tsym, token = spec.rsplit(":", 1)
                expiry = parse_expiry(tsym)
                if expiry is None:
                    raise PullError(f"Cannot parse expiry from tsym '{tsym}'")
                contracts.append({"tsym": tsym, "token": token, "expiry": expiry})
        else:
            contracts = [{"tsym": str(r["tsym"]), "token": str(r.get("token", "")),
                          "expiry": parse_expiry(str(r["tsym"]))} for r in candidates]
            if not contracts:
                raise PullError(
                    "SearchScrip found no SILVER futures. If SearchScrip reports "
                    "'Exchange Not enabled', activate the MCX segment in Prism "
                    "(https://prism.finvasia.com/) or fetch a known token directly "
                    "via --token (e.g. --token 495214 = SILVER04DEC26)."
                )

    segments = plan_segments(contracts, start, end)
    print(f"\nRange {start}..{end} ({args.days}d) -> {len(segments)} contract segment(s):")
    for seg in segments:
        c = seg["contract"]
        print(f"  {seg['start']}..{seg['end']}: {c['tsym']} (token {c['token']}, "
              f"expiry {c['expiry']})")

    # ---- fetch ----
    print("\nFetching TPSeries:", flush=True)
    bars: list[tuple] = []
    for seg in segments:
        bars.extend(fetch_segment(client, args.exch, seg, args.interval))

    # dedupe (day-boundary overlap), sort, keep last write per timestamp
    dedup: dict[datetime, tuple] = {}
    for bar in bars:
        dedup[bar[0]] = bar
    bars = sorted(dedup.values(), key=lambda b: b[0])
    if not bars:
        raise PullError("No bars returned for the planned segments.")

    # ---- report ----
    report = coverage_report(bars, start, end)
    print(f"\nFetched {len(bars)} bars over {report['trading_days']} trading days "
          f"({report['weekday_expected']} weekdays expected)")
    print(f"Session hours: {report['first_hour']:02d}:00 - {report['last_hour']:02d}:59 IST "
          f"| median {report['median_bars_per_day']} bars/day "
          f"(min {report['min_bars_per_day']})")
    print(f"ORB window bars (18:00-18:25): {report['bars_1800_1830']} total | "
          f"18:xx minutes seen: {report['bars_18_minutes']}")
    if report["missing_weekdays"]:
        shown = ", ".join(str(d) for d in report["missing_weekdays"][:12])
        more = len(report["missing_weekdays"]) - 12
        print(f"Weekdays with NO bars: {shown}"
              + (f" (+{more} more)" if more > 0 else "")
              + "  [holidays or coverage gap]")

    # ---- write ----
    out = Path(args.out) if args.out else (
        ROOT / "data" / f"mcx_silver_{args.interval}m_{args.days}d.csv")
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = ["datetime,open,high,low,close,volume"]
    for ts, o, h, l, c, v in bars:
        stamp = ts.strftime("%Y-%m-%d %H:%M:%S") + ts.strftime("%z")
        lines.append(f"{stamp},{o:g},{h:g},{l:g},{c:g},{v}")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nWrote {out} ({out.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PullError as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        sys.exit(1)
