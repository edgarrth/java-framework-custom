"""Owned loopback STS/IMDS fixture for JVM/ELF acceptance; never calls AWS."""
import datetime
import http.server
import os
import tempfile
import threading
from pathlib import Path


class IrsaFixture:
    def __init__(self):
        self.sts_calls = 0
        self.imds_calls = 0
        self.succeed = True
        self.invalid_requests = 0
        self.temporary = tempfile.TemporaryDirectory(prefix="axiz-irsa-")
        self.token = Path(self.temporary.name) / "token"
        self.token.write_text("fixture-token")
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                fixture.imds_calls += 1
                self.send_response(500)
                self.end_headers()

            do_PUT = do_GET

            def do_POST(self):
                request = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
                fixture.sts_calls += 1
                if "AssumeRoleWithWebIdentity" not in request or "fixture-token" not in request:
                    fixture.invalid_requests += 1
                    self.send_error(400)
                    return
                expires = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
                xml = (f'<AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">'
                       f'<AssumeRoleWithWebIdentityResult><Credentials><AccessKeyId>ASIAFIXTURE</AccessKeyId>'
                       f'<SecretAccessKey>fixture-secret</SecretAccessKey><SessionToken>fixture-session</SessionToken>'
                       f'<Expiration>{expires}</Expiration></Credentials><SubjectFromWebIdentityToken>fixture</SubjectFromWebIdentityToken>'
                       f'<AssumedRoleUser><Arn>arn:aws:sts::123456789012:assumed-role/fixture/session</Arn>'
                       f'<AssumedRoleId>fixture:session</AssumedRoleId></AssumedRoleUser>'
                       f'</AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>' if fixture.succeed else
                       '<ErrorResponse><Error><Code>InvalidIdentityToken</Code><Message>fixture denied</Message></Error></ErrorResponse>')
                body = xml.encode()
                self.send_response(200 if fixture.succeed else 400)
                self.send_header("Content-Type", "text/xml")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def environment(self):
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("AWS_", "AXIZ_")) and key not in
                       ("NEXUS_USERNAME", "NEXUS_PASSWORD", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
        endpoint = f"http://127.0.0.1:{self.server.server_port}"
        environment.update(AWS_REGION="us-east-1", AWS_CONFIG_FILE="/dev/null",
                           AWS_SHARED_CREDENTIALS_FILE="/dev/null",
                           AWS_ROLE_ARN="arn:aws:iam::123456789012:role/fixture",
                           AWS_WEB_IDENTITY_TOKEN_FILE=str(self.token),
                           AWS_ENDPOINT_URL_STS=endpoint,
                           AWS_EC2_METADATA_SERVICE_ENDPOINT=endpoint + "/imds")
        return environment

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temporary.cleanup()
