#!/usr/bin/env python3
"""Local zero-secret Mock & hosted server-env OAuth E2E demo server for reviewers."""
import argparse
import base64
from dataclasses import dataclass
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser


OAUTH_PATH_PATTERN = re.compile(r"^/api/v1/auth/oauth/(apple|naver|kakao)$")
ALLOWED_POST_PATHS = {"/api/v1/auth/reissue", "/api/v1/auth/logout"}

MOCK_PERSONAS = {
    "apple-email": {
        "provider": "apple",
        "sub": "mock-apple-user-1",
        "email": "mock.apple1@privaterelay.appleid.com",
        "label": "Apple 기본 회원 (이메일 포함)",
    },
    "apple-alt": {
        "provider": "apple",
        "sub": "mock-apple-user-2",
        "email": "mock.apple2@privaterelay.appleid.com",
        "label": "Apple 2번째 회원 (다른 sub)",
    },
    "naver-email": {
        "provider": "naver",
        "sub": "mock-naver-user-1",
        "email": "mock.naver1@naver.com",
        "label": "Naver 회원 (이메일 포함)",
    },
    "kakao-no-email-1": {
        "provider": "kakao",
        "sub": "900001",
        "email": None,
        "label": "Kakao 무이메일 회원 1 (비비즈 앱 시나리오)",
    },
    "kakao-no-email-2": {
        "provider": "kakao",
        "sub": "900002",
        "email": None,
        "label": "Kakao 무이메일 회원 2 (복수 NULL 공존 검증)",
    },
    "kakao-email": {
        "provider": "kakao",
        "sub": "900003",
        "email": "mock.kakao3@kakao.com",
        "label": "Kakao 이메일 포함 회원",
    },
}


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, val = line.split("=", 1)
        key = key.strip()
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ('"', "'"):
            val = val[1:-1]
        values[key] = val
    return values


def der_ecdsa_to_p1363(der: bytes, part_size: int = 32) -> bytes:
    if len(der) < 8 or der[0] != 0x30:
        raise ValueError("Invalid ECDSA DER signature")
    idx = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    if der[idx] != 0x02:
        raise ValueError("Invalid ECDSA DER r marker")
    r_len = der[idx + 1]
    r_bytes = der[idx + 2 : idx + 2 + r_len]
    idx = idx + 2 + r_len
    if der[idx] != 0x02:
        raise ValueError("Invalid ECDSA DER s marker")
    s_len = der[idx + 1]
    s_bytes = der[idx + 2 : idx + 2 + s_len]
    r_norm = r_bytes.lstrip(b"\x00").rjust(part_size, b"\x00")
    s_norm = s_bytes.lstrip(b"\x00").rjust(part_size, b"\x00")
    if len(r_norm) != part_size or len(s_norm) != part_size:
        raise ValueError("Unexpected ECDSA P-256 coordinate length")
    return r_norm + s_norm


def sign_apple_client_secret(client_id: str, team_id: str, key_id: str, private_key_pem: str) -> str:
    now = int(time.time())
    header = {"alg": "ES256", "kid": key_id}
    claims = {
        "iss": team_id,
        "iat": now,
        "exp": now + 300,
        "aud": "https://appleid.apple.com",
        "sub": client_id,
    }
    signing_input = (
        f"{b64url(json.dumps(header, separators=(',', ':')).encode())}."
        f"{b64url(json.dumps(claims, separators=(',', ':')).encode())}"
    )
    fd, key_path = tempfile.mkstemp(prefix="apple-p8-", suffix=".pem")
    try:
        os.chmod(key_path, 0o600)
        normalized_pem = private_key_pem.replace("\\n", "\n").strip() + "\n"
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(normalized_pem)
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input.encode("ascii"),
            capture_output=True,
            check=True,
        )
    finally:
        if os.path.exists(key_path):
            os.remove(key_path)
    raw_sig = der_ecdsa_to_p1363(proc.stdout)
    return f"{signing_input}.{b64url(raw_sig)}"


class EphemeralRsaKey:
    """In-session RSA-2048 keypair generated via OpenSSL for mock Apple ID Tokens."""

    def __init__(self):
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ziper-mock-apple-")
        self._key_path = Path(self._tmpdir.name) / "mock-apple.pem"
        self.kid = "mock-apple-key-1"
        subprocess.run(
            ["openssl", "genrsa", "-out", str(self._key_path), "2048"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        mod_out = subprocess.check_output(
            ["openssl", "rsa", "-in", str(self._key_path), "-modulus", "-noout"],
            text=True,
        ).strip()
        if not mod_out.startswith("Modulus="):
            raise RuntimeError("Unexpected openssl modulus output")
        modulus_bytes = bytes.fromhex(mod_out.split("=", 1)[1])
        self._n_b64 = b64url(modulus_bytes)
        self._e_b64 = b64url((65537).to_bytes(3, "big"))

    def jwks(self) -> dict:
        return {
            "keys": [
                {
                    "kty": "RSA",
                    "use": "sig",
                    "alg": "RS256",
                    "kid": self.kid,
                    "n": self._n_b64,
                    "e": self._e_b64,
                }
            ]
        }

    def sign_jwt(self, claims: dict) -> str:
        header = {"alg": "RS256", "typ": "JWT", "kid": self.kid}
        signing_input = (
            f"{b64url(json.dumps(header, separators=(',', ':')).encode())}."
            f"{b64url(json.dumps(claims, separators=(',', ':')).encode())}"
        )
        sig = subprocess.check_output(
            ["openssl", "dgst", "-sha256", "-sign", str(self._key_path)],
            input=signing_input.encode("ascii"),
        )
        return f"{signing_input}.{b64url(sig)}"

    def close(self):
        self._tmpdir.cleanup()


class MockOAuthState:
    def __init__(self, rsa_key: EphemeralRsaKey, apple_client_id: str):
        self.rsa_key = rsa_key
        self.apple_client_id = apple_client_id
        self._lock = threading.Lock()
        self.codes: dict[str, tuple[str, str]] = {}
        self.tokens: dict[str, tuple[str, str]] = {}

    def issue_code(self, provider: str, persona_id: str) -> str:
        code = f"mock_code_{provider}_{secrets.token_urlsafe(12)}"
        with self._lock:
            self.codes[code] = (provider, persona_id)
        return code

    def exchange_code(self, provider: str, code: str) -> tuple[int, dict]:
        with self._lock:
            entry = self.codes.pop(code, None)
        if not entry or entry[0] != provider:
            return 400, {"error": "invalid_grant", "error_description": "Unknown mock authorization code"}
        persona = MOCK_PERSONAS[entry[1]]
        if provider == "apple":
            now = int(time.time())
            claims = {
                "iss": "https://appleid.apple.com",
                "aud": self.apple_client_id,
                "sub": persona["sub"],
                "iat": now - 5,
                "exp": now + 600,
            }
            if persona["email"] is not None:
                claims["email"] = persona["email"]
                claims["email_verified"] = True
            id_token = self.rsa_key.sign_jwt(claims)
            return 200, {
                "access_token": f"mock_apple_at_{secrets.token_urlsafe(12)}",
                "token_type": "Bearer",
                "expires_in": 3600,
                "id_token": id_token,
            }
        access_token = f"mock_token_{provider}_{secrets.token_urlsafe(16)}"
        with self._lock:
            self.tokens[access_token] = (provider, entry[1])
        return 200, {
            "access_token": access_token,
            "token_type": "bearer",
            "expires_in": 3600,
        }

    def lookup_userinfo(self, provider: str, auth_header: str) -> tuple[int, dict]:
        if not auth_header or not auth_header.startswith("Bearer "):
            return 401, {"error": "unauthorized"}
        token = auth_header[len("Bearer ") :].strip()
        with self._lock:
            entry = self.tokens.get(token)
        if not entry or entry[0] != provider:
            return 401, {"error": "invalid_token"}
        persona = MOCK_PERSONAS[entry[1]]
        if provider == "naver":
            response = {"id": persona["sub"]}
            if persona["email"] is not None:
                response["email"] = persona["email"]
            return 200, {"resultcode": "00", "message": "success", "response": response}
        payload: dict = {"id": int(persona["sub"])}
        if persona["email"] is not None:
            payload["kakao_account"] = {"email": persona["email"]}
        else:
            payload["kakao_account"] = {}
        return 200, payload


@dataclass(frozen=True)
class DemoConfig:
    mode: str
    backend_url: str
    apple_client_id: str
    apple_team_id: str
    apple_key_id: str
    apple_private_key_pem: str
    naver_client_id: str
    naver_client_secret: str
    kakao_client_id: str
    kakao_client_secret: str

    @classmethod
    def from_env(cls, mode: str, backend_url: str, env: dict[str, str]) -> "DemoConfig":
        apple_pem = env.get("DEMO_APPLE_PRIVATE_KEY", "").strip()
        apple_pem_path = env.get("DEMO_APPLE_PRIVATE_KEY_PATH", "").strip()
        if not apple_pem and apple_pem_path:
            path = Path(apple_pem_path).expanduser()
            if path.is_file():
                apple_pem = path.read_text(encoding="utf-8")
        return cls(
            mode=mode,
            backend_url=backend_url.rstrip("/"),
            apple_client_id=env.get("DEMO_APPLE_CLIENT_ID") or env.get("APPLE_WEB_CLIENT_ID") or "com.ject.ziper.web",
            apple_team_id=env.get("DEMO_APPLE_TEAM_ID", "").strip(),
            apple_key_id=env.get("DEMO_APPLE_KEY_ID", "").strip(),
            apple_private_key_pem=apple_pem,
            naver_client_id=env.get("DEMO_NAVER_CLIENT_ID", "").strip(),
            naver_client_secret=env.get("DEMO_NAVER_CLIENT_SECRET", "").strip(),
            kakao_client_id=env.get("DEMO_KAKAO_CLIENT_ID", "").strip(),
            kakao_client_secret=env.get("DEMO_KAKAO_CLIENT_SECRET", "").strip(),
        )

    def public_config(self) -> dict:
        if self.mode == "mock":
            return {
                "mode": "mock",
                "apple": {"configured": True, "clientId": self.apple_client_id, "codeExchangeConfigured": True},
                "naver": {"configured": True, "clientId": "mock-naver-client"},
                "kakao": {"configured": True, "clientId": "mock-kakao-client"},
                "personas": {
                    pid: {"provider": p["provider"], "label": p["label"], "hasEmail": p["email"] is not None}
                    for pid, p in MOCK_PERSONAS.items()
                },
            }
        return {
            "mode": "env",
            "apple": {
                "configured": bool(self.apple_client_id),
                "clientId": self.apple_client_id,
                "codeExchangeConfigured": bool(self.apple_team_id and self.apple_key_id and self.apple_private_key_pem),
            },
            "naver": {
                "configured": bool(self.naver_client_id and self.naver_client_secret),
                "clientId": self.naver_client_id,
            },
            "kakao": {
                "configured": bool(self.kakao_client_id),
                "clientId": self.kakao_client_id,
            },
        }


def post_form(url: str, params: dict) -> tuple[int, dict]:
    encoded = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=encoded,
        headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"raw": raw}
        return err.code, body


HTML_TEMPLATE = r"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Ziper 소셜 로그인 E2E 리뷰어 데모</title>
  <script type="text/javascript" src="https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"></script>
  <style>
    :root { color-scheme: light; }
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; max-width: 880px; margin: 28px auto; padding: 0 16px 48px; line-height: 1.55; color: #18181b; background: #fafafa; }
    h1 { margin: 0 0 6px; font-size: 22px; }
    code, pre { background: #f4f4f5; padding: 2px 6px; border-radius: 4px; font-size: 12.5px; }
    pre { padding: 14px; overflow-x: auto; white-space: pre-wrap; word-break: break-all; border: 1px solid #e4e4e7; background: #fff; min-height: 96px; }
    button { padding: 10px 14px; font-size: 13.5px; border-radius: 8px; border: 1px solid #d4d4d8; background: #fff; cursor: pointer; margin-right: 6px; margin-bottom: 6px; font-weight: 600; }
    button:disabled { opacity: 0.45; cursor: not-allowed; }
    button.apple { background: #111827; color: #fff; border-color: #111827; }
    button.naver { background: #03c75a; color: #fff; border-color: #03c75a; }
    button.kakao { background: #fee500; color: #191919; border-color: #eab308; }
    .box { border: 1px solid #e4e4e7; border-radius: 12px; padding: 16px 18px; margin: 14px 0; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,0.03); }
    .row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; margin-top: 10px; }
    .card { border: 1px solid #e4e4e7; border-radius: 10px; padding: 12px 14px; background: #fcfcfd; }
    .muted { font-size: 13px; color: #52525b; }
    .badge { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #eef2ff; color: #3730a3; border: 1px solid #c7d2fe; }
    .badge.ok { background: #ecfdf5; color: #065f46; border-color: #a7f3d0; }
    .badge.warn { background: #fffbeb; color: #92400e; border-color: #fde68a; }
    select, input, textarea { width: 100%; box-sizing: border-box; padding: 8px; margin-top: 4px; font-size: 13px; border: 1px solid #d4d4d8; border-radius: 6px; font-family: inherit; }
    textarea, input.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
    label { display: block; font-size: 12px; font-weight: 600; color: #3f3f46; margin-top: 8px; }
    .hidden { display: none; }
    details summary { cursor: pointer; font-weight: 600; font-size: 13px; color: #3f3f46; }
  </style>
</head>
<body>
  <div class="row" style="justify-content:space-between;">
    <div>
      <h1>Ziper OAuth · JWT 통합 E2E 데모</h1>
      <p class="muted" style="margin:0;">
        Origin: <code id="origin"></code> · <span id="modeBadge" class="badge">확인 중...</span>
      </p>
    </div>
    <div>
      <button onclick="checkHealth('/livez')">GET /livez</button>
      <button onclick="checkHealth('/readyz')">GET /readyz</button>
    </div>
  </div>

  <div class="box">
    <strong>1. 원클릭 소셜 로그인 &amp; Ziper JWT 발급</strong>
    <p class="muted" style="margin:6px 0 0;">
      Client Secret·개인키는 서버 프로세스(또는 로컬 Mock IdP)에만 고정되어 있으며 브라우저에 노출되지 않습니다. 버튼을 누르면 인가 → 토큰 교환 → <code>POST /api/v1/auth/oauth/{provider}</code>까지 자동으로 이어집니다.
    </p>

    <div class="grid">
      <div class="card">
        <div class="row" style="justify-content:space-between;">
          <strong>Apple</strong>
          <span id="statusApple" class="badge">-</span>
        </div>
        <div id="mockAppleBox" class="hidden">
          <label>테스트 계정 선택 (Mock)</label>
          <select id="mockApplePersona"></select>
        </div>
        <div style="margin-top:10px;">
          <button id="btnApple" class="apple" onclick="loginApple()">Apple로 로그인</button>
        </div>
      </div>

      <div class="card">
        <div class="row" style="justify-content:space-between;">
          <strong>Naver</strong>
          <span id="statusNaver" class="badge">-</span>
        </div>
        <div id="mockNaverBox" class="hidden">
          <label>테스트 계정 선택 (Mock)</label>
          <select id="mockNaverPersona"></select>
        </div>
        <div style="margin-top:10px;">
          <button id="btnNaver" class="naver" onclick="loginNaver()">Naver로 로그인</button>
        </div>
      </div>

      <div class="card">
        <div class="row" style="justify-content:space-between;">
          <strong>Kakao</strong>
          <span id="statusKakao" class="badge">-</span>
        </div>
        <div id="mockKakaoBox" class="hidden">
          <label>테스트 계정 선택 (Mock)</label>
          <select id="mockKakaoPersona"></select>
        </div>
        <div style="margin-top:10px;">
          <button id="btnKakao" class="kakao" onclick="loginKakao(false)">Kakao로 로그인</button>
          <button id="btnKakaoSameTab" onclick="loginKakao(true)" style="font-size:12px;">현재 창으로 로그인</button>
        </div>
      </div>
    </div>
  </div>

  <div class="box">
    <div class="row" style="justify-content:space-between;">
      <strong>2. 발급된 세션 생명주기 확인 (회전 · 재사용 차단 · 로그아웃)</strong>
      <button onclick="clearResultLog()">로그 비우기</button>
    </div>
    <div style="margin-top:10px;">
      <button onclick="decodeCurrentTokens()">현재 JWT Claims 디코드</button>
      <button onclick="callReissue(false)">POST /api/v1/auth/reissue (토큰 회전)</button>
      <button onclick="callReissue(true)">이전 Refresh Token 재사용 테스트 (401 기대)</button>
      <button onclick="callLogout()">POST /api/v1/auth/logout (204 기대)</button>
    </div>
    <div style="margin-top:8px;">
      <pre id="result">대기 중... 위의 소셜 로그인 버튼 중 하나를 클릭하세요.</pre>
    </div>
  </div>

  <div class="box">
    <details>
      <summary>고급: 수동 소셜 토큰 직접 전송 (iOS 네이티브 토큰 테스트 등)</summary>
      <div class="row" style="margin-top:10px;">
        <select id="manualProvider" style="max-width:220px;">
          <option value="apple">apple (id_token)</option>
          <option value="naver">naver (access_token)</option>
          <option value="kakao">kakao (access_token)</option>
        </select>
      </div>
      <label>소셜 토큰 (<code>request.token</code>)</label>
      <textarea id="manualToken" rows="3" placeholder="Apple id_token 또는 Naver/Kakao access_token 붙여넣기"></textarea>
      <div style="margin-top:8px;">
        <button onclick="callBackendLogin(document.getElementById('manualProvider').value, document.getElementById('manualToken').value)">수동 토큰으로 로그인</button>
      </div>
    </details>
  </div>

  <script>
    const originUrl = window.location.origin;
    document.getElementById("origin").textContent = originUrl;

    let demoConfig = null;
    let latestAccessToken = null;
    let latestRefreshToken = null;
    let previousRefreshToken = null;
    let pendingState = {};
    const handledCodes = new Set();
    const logEntries = [];

    function log(label, payload) {
      logEntries.unshift(`[${new Date().toLocaleTimeString()}] ${label}\n` + JSON.stringify(payload, null, 2));
      if (logEntries.length > 8) logEntries.pop();
      document.getElementById("result").textContent = logEntries.join("\n\n----------------------------------------\n\n");
    }

    function clearResultLog() {
      logEntries.length = 0;
      document.getElementById("result").textContent = "대기 중...";
    }

    function decodeJwtPayload(jwt) {
      if (!jwt || jwt.split(".").length < 2) return null;
      const b64 = jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      const jsonStr = decodeURIComponent(escape(atob(b64)));
      return JSON.parse(jsonStr);
    }

    async function getJson(path) {
      const res = await fetch(path);
      const text = await res.text();
      return { status: res.status, body: text ? JSON.parse(text) : null };
    }

    async function postJson(path, body) {
      const res = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
      const text = await res.text();
      let parsed = text;
      try { parsed = text ? JSON.parse(text) : null; } catch (_) {}
      return { status: res.status, requestId: res.headers.get("x-request-id"), body: parsed };
    }

    async function checkHealth(path) {
      const res = await fetch(path);
      const text = await res.text();
      let parsed = text;
      try { parsed = text ? JSON.parse(text) : null; } catch (_) {}
      log(`GET ${path}`, { status: res.status, cacheControl: res.headers.get("cache-control"), body: parsed });
    }

    async function loadConfig() {
      const res = await getJson("/test/config");
      demoConfig = res.body;
      const badge = document.getElementById("modeBadge");
      if (demoConfig.mode === "mock") {
        badge.textContent = "LOCAL MOCK 모드 (무비밀)";
        badge.className = "badge ok";
        ["Apple", "Naver", "Kakao"].forEach(p => {
          document.getElementById(`mock${p}Box`).classList.remove("hidden");
        });
        Object.entries(demoConfig.personas || {}).forEach(([id, info]) => {
          const opt = document.createElement("option");
          opt.value = id;
          opt.textContent = info.label;
          const target = info.provider === "apple" ? "mockApplePersona" : (info.provider === "naver" ? "mockNaverPersona" : "mockKakaoPersona");
          document.getElementById(target).appendChild(opt);
        });
      } else {
        badge.textContent = "HOSTED ENV 모드 (서버 비밀 고정)";
        badge.className = "badge ok";
      }
      setStatus("statusApple", "btnApple", demoConfig.apple?.configured, demoConfig.apple?.codeExchangeConfigured ? "Code+ID 준비" : "ID Token 준비");
      setStatus("statusNaver", "btnNaver", demoConfig.naver?.configured, "준비됨");
      setStatus("statusKakao", "btnKakao", demoConfig.kakao?.configured, "준비됨");
      document.getElementById("btnKakaoSameTab").disabled = !demoConfig.kakao?.configured;
    }

    function setStatus(badgeId, btnId, ok, okLabel) {
      const el = document.getElementById(badgeId);
      const btn = document.getElementById(btnId);
      if (ok) {
        el.textContent = okLabel;
        el.className = "badge ok";
        btn.disabled = false;
      } else {
        el.textContent = "서버 미설정";
        el.className = "badge warn";
        btn.disabled = true;
      }
    }

    function openOAuthPopup(url, name) {
      const popup = window.open(url, name, "width=520,height=640");
      if (!popup) {
        log("팝업 차단 감지", { message: "브라우저 팝업 차단을 허용하거나 '현재 창으로 로그인' 버튼을 사용해 주세요.", url });
      }
      return popup;
    }

    async function loginApple() {
      if (!demoConfig) await loadConfig();
      if (demoConfig.mode === "mock") {
        const state = crypto.randomUUID();
        const persona = document.getElementById("mockApplePersona").value;
        pendingState.apple = state;
        const url = "/mock/apple/authorize?" + new URLSearchParams({
          client_id: demoConfig.apple.clientId,
          redirect_uri: originUrl + "/callback/apple",
          state,
          persona
        }).toString();
        openOAuthPopup(url, "mock_apple_oauth");
        return;
      }
      try {
        const state = crypto.randomUUID();
        AppleID.auth.init({
          clientId: demoConfig.apple.clientId,
          scope: "name email",
          redirectURI: originUrl + "/callback",
          state,
          usePopup: true
        });
        const response = await AppleID.auth.signIn();
        const code = response?.authorization?.code || "";
        let idToken = response?.authorization?.id_token || "";
        if (demoConfig.apple.codeExchangeConfigured && code) {
          const ex = await postJson("/test/exchange/apple", { code, redirectUri: originUrl + "/callback" });
          log("Apple /auth/token 교환 결과", ex);
          if (ex.status === 200 && ex.body?.id_token) {
            idToken = ex.body.id_token;
          }
        }
        await callBackendLogin("apple", idToken);
      } catch (err) {
        log("Apple 로그인 실패", err);
      }
    }

    async function loginNaver() {
      if (!demoConfig) await loadConfig();
      const state = crypto.randomUUID();
      pendingState.naver = state;
      const redirectUri = originUrl + "/callback/naver";
      if (demoConfig.mode === "mock") {
        const persona = document.getElementById("mockNaverPersona").value;
        const url = "/mock/naver/authorize?" + new URLSearchParams({
          client_id: demoConfig.naver.clientId,
          redirect_uri: redirectUri,
          state,
          persona
        }).toString();
        openOAuthPopup(url, "mock_naver_oauth");
        return;
      }
      const url = "https://nid.naver.com/oauth2.0/authorize?" + new URLSearchParams({
        response_type: "code",
        client_id: demoConfig.naver.clientId,
        redirect_uri: redirectUri,
        state
      }).toString();
      openOAuthPopup(url, "naver_oauth");
    }

    async function loginKakao(sameTab) {
      if (!demoConfig) await loadConfig();
      const state = crypto.randomUUID();
      const redirectUri = originUrl + "/callback/kakao";
      pendingState.kakao = state;
      sessionStorage.setItem("ziper.kakaoPendingState", state);
      sessionStorage.setItem("ziper.kakaoRedirectUri", redirectUri);
      const url = (demoConfig.mode === "mock" ? "/mock/kakao/authorize?" : "https://kauth.kakao.com/oauth/authorize?") + new URLSearchParams({
        response_type: "code",
        client_id: demoConfig.kakao.clientId,
        redirect_uri: redirectUri,
        state,
        ...(demoConfig.mode === "mock" ? { persona: document.getElementById("mockKakaoPersona").value } : {})
      }).toString();
      if (sameTab) {
        window.location.href = url;
        return;
      }
      openOAuthPopup(url, "kakao_oauth");
    }

    async function handleCallbackPayload(data) {
      if (!data || data.type !== "ziper-oauth-callback") return;
      const { provider, code, state, error, error_description } = data;
      if (code) {
        const key = `${provider}:${code}`;
        if (handledCodes.has(key)) return;
        handledCodes.add(key);
      }
      if (error) {
        log(`${provider} 콜백 오류`, { error, error_description });
        return;
      }
      const expectedState = pendingState[provider] || (provider === "kakao" ? sessionStorage.getItem("ziper.kakaoPendingState") : null);
      if (expectedState && state && state !== expectedState) {
        log(`${provider} State 불일치 오류`, { expected: expectedState, actual: state });
        return;
      }
      const redirectUri = (provider === "kakao" && sessionStorage.getItem("ziper.kakaoRedirectUri")) || (originUrl + `/callback/${provider}`);
      const ex = await postJson(`/test/exchange/${provider}`, { code, state, redirectUri });
      log(`${provider.toUpperCase()} 토큰 교환 결과`, { status: ex.status, hasAccessToken: Boolean(ex.body?.access_token), hasIdToken: Boolean(ex.body?.id_token), scope: ex.body?.scope || null });
      const backendToken = provider === "apple" ? ex.body?.id_token : ex.body?.access_token;
      if (ex.status === 200 && backendToken) {
        await callBackendLogin(provider, backendToken);
      }
    }

    window.addEventListener("message", async (event) => {
      if (event.origin !== window.location.origin) return;
      await handleCallbackPayload(event.data);
    });
    if ("BroadcastChannel" in window) {
      new BroadcastChannel("ziper-oauth-callback").onmessage = async (event) => {
        await handleCallbackPayload(event.data);
      };
    }

    async function callBackendLogin(provider, rawToken) {
      const token = (rawToken || "").trim();
      if (!token) {
        log("오류", { message: "전달할 소셜 토큰이 비어 있습니다." });
        return;
      }
      const res = await postJson(`/api/v1/auth/oauth/${provider}`, { token });
      if (res.status === 200 && res.body?.refreshToken) {
        previousRefreshToken = latestRefreshToken;
        latestAccessToken = res.body.accessToken;
        latestRefreshToken = res.body.refreshToken;
      }
      const note = res.status === 400 ? " (현재 백엔드 브랜치에 해당 프로바이더 전략이 미포함일 수 있습니다)" : "";
      log(`Ziper 백엔드 POST /api/v1/auth/oauth/${provider} 결과${note}`, {
        status: res.status,
        requestId: res.requestId,
        isNewUser: res.body?.isNewUser ?? null,
        accessClaims: decodeJwtPayload(res.body?.accessToken),
        refreshClaims: decodeJwtPayload(res.body?.refreshToken),
        body: res.status === 200 ? { receivedAccessToken: true, receivedRefreshToken: true, isNewUser: res.body?.isNewUser } : res.body
      });
    }

    function decodeCurrentTokens() {
      log("발급된 Ziper JWT Claims", {
        accessClaims: decodeJwtPayload(latestAccessToken),
        refreshClaims: decodeJwtPayload(latestRefreshToken)
      });
    }

    async function callReissue(usePrevious) {
      const target = usePrevious ? previousRefreshToken : latestRefreshToken;
      if (!target) {
        log("오류", { message: usePrevious ? "이전 refreshToken 기록이 없습니다(먼저 reissue 1회 실행 필요)." : "먼저 로그인해서 refreshToken을 발급받아 주세요." });
        return;
      }
      const res = await postJson("/api/v1/auth/reissue", { refreshToken: target });
      if (!usePrevious && res.status === 200 && res.body?.refreshToken) {
        previousRefreshToken = latestRefreshToken;
        latestAccessToken = res.body.accessToken;
        latestRefreshToken = res.body.refreshToken;
      }
      log(usePrevious ? "구 Refresh Token 재사용 차단 테스트" : "POST /api/v1/auth/reissue 결과", {
        status: res.status,
        requestId: res.requestId,
        accessClaims: res.status === 200 ? decodeJwtPayload(res.body?.accessToken) : null,
        body: res.status === 200 ? { rotated: true } : res.body
      });
    }

    async function callLogout() {
      if (!latestRefreshToken) {
        log("오류", { message: "유효한 refreshToken이 없습니다." });
        return;
      }
      const res = await postJson("/api/v1/auth/logout", { refreshToken: latestRefreshToken });
      if (res.status === 204) {
        previousRefreshToken = latestRefreshToken;
        latestRefreshToken = null;
        latestAccessToken = null;
      }
      log("POST /api/v1/auth/logout 결과", { status: res.status, requestId: res.requestId, body: res.body });
    }

    loadConfig().then(() => {
      const sp = new URLSearchParams(window.location.search);
      const cbProvider = sp.get("cb_provider");
      if (!cbProvider) return;
      const payload = {
        type: "ziper-oauth-callback",
        provider: cbProvider,
        code: sp.get("code"),
        state: sp.get("state"),
        error: sp.get("error"),
        error_description: sp.get("error_description")
      };
      window.history.replaceState({}, document.title, window.location.pathname);
      handleCallbackPayload(payload);
    });
  </script>
</body>
</html>
"""

CALLBACK_HTML = """<!doctype html>
<html lang="ko">
<head><meta charset="utf-8" /><title>OAuth Callback</title></head>
<body>
  <pre id="msg">콜백 처리 중...</pre>
  <script>
    const params = new URLSearchParams(window.location.search);
    const provider = __PROVIDER_JSON__;
    const payload = {
      type: "ziper-oauth-callback",
      provider,
      code: params.get("code"),
      state: params.get("state"),
      error: params.get("error"),
      error_description: params.get("error_description")
    };
    let delivered = false;
    if (window.opener) {
      window.opener.postMessage(payload, window.location.origin);
      delivered = true;
    } else if ("BroadcastChannel" in window) {
      const bc = new BroadcastChannel("ziper-oauth-callback");
      bc.postMessage(payload);
      delivered = true;
    }
    if (window.opener && delivered) {
      setTimeout(() => window.close(), 150);
    } else if (!window.opener) {
      const next = new URLSearchParams(window.location.search);
      next.set("cb_provider", provider);
      window.location.replace("/?" + next.toString());
    } else {
      document.getElementById("msg").textContent = JSON.stringify(payload, null, 2);
    }
  </script>
</body>
</html>
"""


def make_handler(config: DemoConfig, mock_state: MockOAuthState | None):
    class DemoHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def _send_json(self, status: int, payload: dict):
            data = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _send_html(self, html: str, status: int = 200):
            data = html.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _read_json_body(self) -> dict | None:
            length = int(self.headers.get("Content-Length", "0"))
            try:
                return json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._send_json(400, {"error": "Invalid JSON"})
                return None

        def do_GET(self):
            parsed = urllib.parse.urlsplit(self.path)
            path = parsed.path
            if path == "/test/config":
                self._send_json(200, config.public_config())
                return
            if path in ("/callback/apple", "/callback/naver", "/callback/kakao"):
                provider = path.rsplit("/", 1)[-1]
                self._send_html(CALLBACK_HTML.replace("__PROVIDER_JSON__", json.dumps(provider)))
                return
            if path == "/" or path == "/callback":
                self._send_html(HTML_TEMPLATE)
                return
            if path in ("/livez", "/readyz"):
                self._proxy("GET", path)
                return
            if mock_state is not None:
                if path == "/mock/apple/keys":
                    self._send_json(200, mock_state.rsa_key.jwks())
                    return
                if path in ("/mock/apple/authorize", "/mock/naver/authorize", "/mock/kakao/authorize"):
                    provider = path.split("/")[2]
                    qs = urllib.parse.parse_qs(parsed.query)
                    redirect_uri = qs.get("redirect_uri", [""])[0]
                    state = qs.get("state", [""])[0]
                    persona = qs.get("persona", [""])[0]
                    if persona not in MOCK_PERSONAS or MOCK_PERSONAS[persona]["provider"] != provider:
                        self._send_json(400, {"error": "invalid_persona"})
                        return
                    parsed_redirect = urllib.parse.urlsplit(redirect_uri)
                    if parsed_redirect.scheme not in ("http", "https") or parsed_redirect.hostname not in (
                        "127.0.0.1",
                        "localhost",
                    ):
                        self._send_json(400, {"error": "invalid_redirect_uri"})
                        return
                    code = mock_state.issue_code(provider, persona)
                    sep = "&" if "?" in redirect_uri else "?"
                    location = f"{redirect_uri}{sep}{urllib.parse.urlencode({'code': code, 'state': state})}"
                    self.send_response(302)
                    self.send_header("Location", location)
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    return
                if path == "/mock/naver/v1/nid/me":
                    status, payload = mock_state.lookup_userinfo("naver", self.headers.get("Authorization", ""))
                    self._send_json(status, payload)
                    return
                if path == "/mock/kakao/v2/user/me":
                    status, payload = mock_state.lookup_userinfo("kakao", self.headers.get("Authorization", ""))
                    self._send_json(status, payload)
                    return
            self.send_error(404)

        def do_POST(self):
            parsed = urllib.parse.urlsplit(self.path)
            path = parsed.path
            if path in ("/test/exchange/apple", "/test/exchange/naver", "/test/exchange/kakao"):
                self._handle_exchange(path.rsplit("/", 1)[-1])
                return
            if path.startswith("/callback"):
                self._send_html("<html><body>OK</body></html>")
                return
            if path in ALLOWED_POST_PATHS or OAUTH_PATH_PATTERN.match(path):
                self._proxy("POST", path)
                return
            self.send_error(404)

        def _handle_exchange(self, provider: str):
            body = self._read_json_body()
            if body is None:
                return
            code = (body.get("code") or "").strip()
            if not code:
                self._send_json(400, {"error": "Missing authorization code"})
                return
            if config.mode == "mock" and mock_state is not None:
                status, resp = mock_state.exchange_code(provider, code)
                self._send_json(status, resp)
                return
            try:
                if provider == "apple":
                    if not (config.apple_team_id and config.apple_key_id and config.apple_private_key_pem):
                        self._send_json(400, {"error": "Apple Auth Code credentials are not configured on server"})
                        return
                    client_secret = sign_apple_client_secret(
                        config.apple_client_id,
                        config.apple_team_id,
                        config.apple_key_id,
                        config.apple_private_key_pem,
                    )
                    params = {
                        "client_id": config.apple_client_id,
                        "client_secret": client_secret,
                        "code": code,
                        "grant_type": "authorization_code",
                    }
                    if body.get("redirectUri"):
                        params["redirect_uri"] = body["redirectUri"]
                    status, resp = post_form("https://appleid.apple.com/auth/token", params)
                    self._send_json(status, resp)
                elif provider == "naver":
                    if not (config.naver_client_id and config.naver_client_secret):
                        self._send_json(400, {"error": "Naver credentials are not configured on server"})
                        return
                    status, resp = post_form(
                        "https://nid.naver.com/oauth2.0/token",
                        {
                            "grant_type": "authorization_code",
                            "client_id": config.naver_client_id,
                            "client_secret": config.naver_client_secret,
                            "code": code,
                            "state": body.get("state", ""),
                        },
                    )
                    self._send_json(status, resp)
                elif provider == "kakao":
                    if not config.kakao_client_id:
                        self._send_json(400, {"error": "Kakao client ID is not configured on server"})
                        return
                    params = {
                        "grant_type": "authorization_code",
                        "client_id": config.kakao_client_id,
                        "redirect_uri": body.get("redirectUri", ""),
                        "code": code,
                    }
                    if config.kakao_client_secret:
                        params["client_secret"] = config.kakao_client_secret
                    status, resp = post_form("https://kauth.kakao.com/oauth/token", params)
                    self._send_json(status, resp)
            except (ValueError, subprocess.CalledProcessError):
                self._send_json(400, {"error": "Server-side OAuth client assertion signing failed"})

        def _proxy(self, method: str, path: str):
            length = int(self.headers.get("Content-Length", "0"))
            req_body = self.rfile.read(length) if length > 0 else None
            headers = {}
            for key in ("Content-Type", "Authorization", "User-Agent"):
                if key in self.headers:
                    headers[key] = self.headers[key]
            req = urllib.request.Request(
                f"{config.backend_url}{path}",
                data=req_body,
                headers=headers,
                method=method,
            )
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = resp.read()
                    self.send_response(resp.status)
                    for h_key in ("Content-Type", "X-Request-Id", "Cache-Control"):
                        val = resp.headers.get(h_key)
                        if val:
                            self.send_header(h_key, val)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
            except urllib.error.HTTPError as err:
                data = err.read()
                self.send_response(err.code)
                for h_key in ("Content-Type", "X-Request-Id", "WWW-Authenticate"):
                    val = err.headers.get(h_key)
                    if val:
                        self.send_header(h_key, val)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except urllib.error.URLError:
                self._send_json(502, {"error": "Local backend is unavailable"})

    return DemoHandler


def tcp_open(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex((host, port)) == 0


def ensure_local_postgres(repo_root: Path):
    if not (repo_root / ".env").is_file():
        subprocess.run([str(repo_root / "scripts/local-setup.sh")], cwd=repo_root, check=True)
    if not tcp_open("127.0.0.1", 5432):
        subprocess.run(["docker", "compose", "up", "-d", "db"], cwd=repo_root, check=True)
        for _ in range(30):
            if tcp_open("127.0.0.1", 5432):
                return
            time.sleep(1)
        raise RuntimeError("PostgreSQL did not become ready on 127.0.0.1:5432")


def start_backend(repo_root: Path, backend_port: int, ui_port: int, apple_client_id: str, mode: str) -> subprocess.Popen:
    ensure_local_postgres(repo_root)
    env = dict(os.environ)
    env.update(parse_env_file(repo_root / ".env"))
    env["PORT"] = str(backend_port)
    env["SPRING_PROFILES_ACTIVE"] = "local"
    env["APP_RUNTIME_MODE"] = "local"
    env["SWAGGER_ENABLED"] = "true"
    existing_ids = [item.strip() for item in env.get("APPLE_CLIENT_IDS", "").split(",") if item.strip()]
    if apple_client_id not in existing_ids:
        existing_ids.append(apple_client_id)
    env["APPLE_CLIENT_IDS"] = ",".join(existing_ids)
    if mode == "mock":
        base = f"http://127.0.0.1:{ui_port}"
        env["APPLE_ISSUER"] = "https://appleid.apple.com"
        env["APPLE_JWKS_URI"] = f"{base}/mock/apple/keys"
        env["NAVER_USER_INFO_URI"] = f"{base}/mock/naver/v1/nid/me"
        env["KAKAO_USER_INFO_URI"] = f"{base}/mock/kakao/v2/user/me"
    proc = subprocess.Popen(
        [str(repo_root / "gradlew"), "bootRun"],
        cwd=repo_root,
        env=env,
        start_new_session=True,
    )
    deadline = time.time() + 120
    ready_url = f"http://127.0.0.1:{backend_port}/readyz"
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"Backend exited early with code {proc.returncode}")
        try:
            with urllib.request.urlopen(ready_url, timeout=1.5) as resp:
                if resp.status == 200:
                    return proc
        except Exception:
            time.sleep(1)
    terminate_process_group(proc)
    raise TimeoutError("Timed out waiting for local backend /readyz")


def terminate_process_group(proc: subprocess.Popen | None):
    if proc is None or proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=10)
    except Exception:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run Ziper OAuth E2E reviewer demo UI")
    parser.add_argument("--mode", choices=("mock", "env"), default="mock", help="mock=zero-secret local IdP, env=server env credentials")
    parser.add_argument("--env-file", default=".env.oauth-demo", help="Optional gitignored env file for --mode env")
    parser.add_argument("--ui-port", type=int, default=5173, help="Local port for the demo web UI")
    parser.add_argument("--backend-port", type=int, default=8080, help="Local backend port")
    parser.add_argument("--backend-url", default=None, help="Explicit backend URL when using --no-backend")
    parser.add_argument("--no-backend", action="store_true", help="Do not launch local docker db / bootRun")
    parser.add_argument("--open", action="store_true", help="Open demo URL in default browser when ready")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = Path(__file__).resolve().parent.parent
    merged_env = dict(os.environ)
    env_file = Path(args.env_file)
    if not env_file.is_absolute():
        env_file = repo_root / env_file
    merged_env.update(parse_env_file(env_file))

    backend_url = args.backend_url or f"http://127.0.0.1:{args.backend_port}"
    config = DemoConfig.from_env(args.mode, backend_url, merged_env)

    rsa_key = None
    mock_state = None
    if args.mode == "mock":
        if not shutil.which("openssl"):
            print("openssl binary is required for --mode mock", file=sys.stderr)
            return 1
        rsa_key = EphemeralRsaKey()
        mock_state = MockOAuthState(rsa_key, config.apple_client_id)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.ui_port), make_handler(config, mock_state))
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    backend_proc = None
    try:
        if not args.no_backend:
            print(f"[oauth-demo] Starting local backend on port {args.backend_port} (mode={args.mode})...", flush=True)
            backend_proc = start_backend(repo_root, args.backend_port, args.ui_port, config.apple_client_id, args.mode)
        ui_url = f"http://127.0.0.1:{args.ui_port}/"
        print(f"[oauth-demo] Ready at {ui_url} (backend={config.backend_url}, mode={args.mode})", flush=True)
        print("[oauth-demo] Press Ctrl+C to stop.", flush=True)
        if args.open:
            webbrowser.open(ui_url)
        while True:
            if backend_proc is not None and backend_proc.poll() is not None:
                print(f"[oauth-demo] Backend exited with code {backend_proc.returncode}", file=sys.stderr)
                return backend_proc.returncode or 1
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[oauth-demo] Shutting down...", flush=True)
        return 0
    finally:
        terminate_process_group(backend_proc)
        server.shutdown()
        server.server_close()
        if rsa_key is not None:
            rsa_key.close()


if __name__ == "__main__":
    raise SystemExit(main())
