#!/usr/bin/env python3
"""Exercise the local OAuth E2E demo server and mock IdP endpoints."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request


spec = importlib.util.spec_from_file_location("oauth_demo", Path(__file__).with_name("oauth-e2e-demo.py"))
oauth_demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oauth_demo)


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class OAuthDemoTests(unittest.TestCase):
    def setUp(self):
        self.rsa_key = oauth_demo.EphemeralRsaKey()
        self.mock_state = oauth_demo.MockOAuthState(self.rsa_key, "com.ject.ziper.web")
        self.config = oauth_demo.DemoConfig(
            mode="mock",
            backend_url="http://127.0.0.1:59999",
            apple_client_id="com.ject.ziper.web",
            apple_team_id="",
            apple_key_id="",
            apple_private_key_pem="",
            naver_client_id="",
            naver_client_secret="",
            kakao_client_id="",
            kakao_client_secret="",
        )
        self.server = oauth_demo.http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0),
            oauth_demo.make_handler(self.config, self.mock_state),
        )
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.rsa_key.close()

    def _get(self, path: str, headers: dict | None = None) -> tuple[int, dict, str]:
        opener = urllib.request.build_opener(NoRedirectHandler())
        req = urllib.request.Request(f"{self.base_url}{path}", headers=headers or {}, method="GET")
        try:
            with opener.open(req, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read().decode("utf-8")
        except urllib.error.HTTPError as err:
            with err:
                return err.code, dict(err.headers), err.read().decode("utf-8", errors="replace")

    def _post_json(self, path: str, body: dict) -> tuple[int, dict | str]:
        req = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                raw = resp.read().decode("utf-8")
                return resp.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as err:
            with err:
                raw = err.read().decode("utf-8", errors="replace")
                try:
                    return err.code, json.loads(raw)
                except json.JSONDecodeError:
                    return err.code, raw

    def test_ephemeral_rsa_signs_jwt_verifiable_with_public_key_and_jwks(self):
        jwks = self.rsa_key.jwks()
        self.assertEqual(len(jwks["keys"]), 1)
        jwk = jwks["keys"][0]
        self.assertEqual(jwk["kty"], "RSA")
        self.assertEqual(jwk["alg"], "RS256")
        token = self.rsa_key.sign_jwt({"iss": "https://appleid.apple.com", "sub": "user-1", "aud": "com.ject.ziper.web"})
        header_b64, payload_b64, sig_b64 = token.split(".")
        signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
        sig_bytes = base64.urlsafe_b64decode(sig_b64 + "=" * (-len(sig_b64) % 4))

        with tempfile.TemporaryDirectory() as tmpdir:
            pub_path = Path(tmpdir) / "pub.pem"
            sig_path = Path(tmpdir) / "sig.bin"
            subprocess.run(
                ["openssl", "rsa", "-in", str(self.rsa_key._key_path), "-pubout", "-out", str(pub_path)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=True,
            )
            sig_path.write_bytes(sig_bytes)
            verify = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", str(pub_path), "-signature", str(sig_path)],
                input=signing_input,
                capture_output=True,
                check=True,
            )
            self.assertIn(b"Verified OK", verify.stdout)

    def test_env_config_never_exposes_secrets_to_browser(self):
        secret_cfg = oauth_demo.DemoConfig(
            mode="env",
            backend_url="http://127.0.0.1:8080",
            apple_client_id="com.ject.ziper.web",
            apple_team_id="TEAMSECRET1",
            apple_key_id="KEYSECRET1",
            apple_private_key_pem="-----BEGIN PRIVATE KEY-----\nTOPSECRET\n-----END PRIVATE KEY-----",
            naver_client_id="naver-pub-id",
            naver_client_secret="NAVER_TOP_SECRET",
            kakao_client_id="kakao-pub-id",
            kakao_client_secret="KAKAO_TOP_SECRET",
        )
        serialized = json.dumps(secret_cfg.public_config())
        for secret in ("TEAMSECRET1", "KEYSECRET1", "TOPSECRET", "NAVER_TOP_SECRET", "KAKAO_TOP_SECRET"):
            self.assertNotIn(secret, serialized)
        self.assertTrue(secret_cfg.public_config()["apple"]["codeExchangeConfigured"])
        self.assertTrue(secret_cfg.public_config()["naver"]["configured"])
        self.assertTrue(secret_cfg.public_config()["kakao"]["configured"])

    def test_mock_oauth_flows_apple_naver_kakao_including_email_less(self):
        # 1. Open-redirect protection on mock authorize
        status, _, _ = self._get("/mock/apple/authorize?redirect_uri=http://localhost.evil.com/cb&state=s1&persona=apple-email")
        self.assertEqual(status, 400)

        # 2. Apple mock authorize -> /test/exchange/apple -> id_token + JWKS match
        status, headers, _ = self._get(
            f"/mock/apple/authorize?redirect_uri={urllib.parse.quote(self.base_url + '/callback/apple')}&state=st-a&persona=apple-email"
        )
        self.assertEqual(status, 302)
        code = urllib.parse.parse_qs(urllib.parse.urlsplit(headers["Location"]).query)["code"][0]
        ex_status, ex_body = self._post_json("/test/exchange/apple", {"code": code})
        self.assertEqual(ex_status, 200)
        self.assertIn("id_token", ex_body)

        # 3. Kakao mock no-email flow (V4 scenario)
        status, headers, _ = self._get(
            f"/mock/kakao/authorize?redirect_uri={urllib.parse.quote(self.base_url + '/callback/kakao')}&state=st-k&persona=kakao-no-email-1"
        )
        self.assertEqual(status, 302)
        kakao_code = urllib.parse.parse_qs(urllib.parse.urlsplit(headers["Location"]).query)["code"][0]
        ex_status, ex_body = self._post_json("/test/exchange/kakao", {"code": kakao_code})
        self.assertEqual(ex_status, 200)
        info_status, _, info_raw = self._get(
            "/mock/kakao/v2/user/me",
            headers={"Authorization": f"Bearer {ex_body['access_token']}"},
        )
        self.assertEqual(info_status, 200)
        info = json.loads(info_raw)
        self.assertEqual(info["id"], 900001)
        self.assertEqual(info["kakao_account"], {})

        # 4. Naver mock flow
        status, headers, _ = self._get(
            f"/mock/naver/authorize?redirect_uri={urllib.parse.quote(self.base_url + '/callback/naver')}&state=st-n&persona=naver-email"
        )
        self.assertEqual(status, 302)
        naver_code = urllib.parse.parse_qs(urllib.parse.urlsplit(headers["Location"]).query)["code"][0]
        ex_status, ex_body = self._post_json("/test/exchange/naver", {"code": naver_code})
        self.assertEqual(ex_status, 200)
        info_status, _, info_raw = self._get(
            "/mock/naver/v1/nid/me",
            headers={"Authorization": f"Bearer {ex_body['access_token']}"},
        )
        self.assertEqual(info_status, 200)
        naver_info = json.loads(info_raw)
        self.assertEqual(naver_info["resultcode"], "00")
        self.assertEqual(naver_info["response"]["email"], "mock.naver1@naver.com")

    def test_proxy_rejects_unlisted_paths(self):
        status, _, _ = self._get("/actuator/env")
        self.assertEqual(status, 404)
        status, _ = self._post_json("/api/v1/users/me", {})
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
