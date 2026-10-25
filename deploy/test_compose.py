"""Check resolved deployment boundaries without an AWS account, real secrets, or running services."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class DeploymentTests(unittest.TestCase):
    root = Path(__file__).resolve().parents[1]

    def resolve(self, missing=None):
        # Start with a controlled environment so a developer's private .env cannot affect this check.
        values = {
            'SITE_ADDRESS': '203.0.113.10.sslip.io', 'APP_PASSWORD': 'test-account-only',
            'POSTGRES_PASSWORD': 'test-database-only', 'GITHUB_WEBHOOK_SECRET': 'test-webhook-only',
            'GITHUB_INSTALLATION_STATE_SECRET': 'test-state-only-012345678901234567890123456789',
            'GITHUB_APP_ID': '123', 'GITHUB_APP_SLUG': 'test-app', 'GITHUB_CONNECTION_ACCOUNT_ID': '456',
            'GITHUB_APP_PRIVATE_KEY_FILE': '/tmp/changeguard-test-only.pem',
        }
        values.pop(missing, None)
        with tempfile.TemporaryDirectory() as directory:
            configuration = Path(directory) / '.env'
            configuration.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
            # --env-file bypasses any real deployment .env; shell values are restricted as well.
            environment = {key: value for key, value in os.environ.items()
                           if key in ('PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'XDG_RUNTIME_DIR')}
            return subprocess.run(['docker', 'compose', '--env-file', str(configuration),
                                   '-f', str(self.root / 'deploy/compose.yaml'), 'config', '--format', 'json'],
                                  capture_output=True, text=True, env=environment, check=False)

    def configuration(self):
        result = self.resolve()
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_only_https_edge_publishes_ports_and_no_source_is_mounted(self):
        configuration = self.configuration()
        services = configuration['services']
        self.assertEqual(set(services), {'frontend', 'connector-service', 'change-service', 'postgres', 'kafka', 'schema-registry'})
        for name, service in services.items():
            self.assertEqual(service['restart'], 'unless-stopped')
            self.assertGreater(int(service['mem_limit']), 0)
            self.assertEqual(service['logging']['options']['max-size'], '10m')
            if name != 'frontend':
                self.assertFalse(service.get('ports'), name)
            self.assertFalse(any(volume['type'] == 'bind' for volume in service.get('volumes', [])), name)
        self.assertEqual({str(port['published']) for port in services['frontend']['ports']}, {'80', '443'})
        self.assertEqual(set(configuration['volumes']), {'certificates', 'postgres_data', 'kafka_data'})

    def test_one_scoped_account_and_no_secrets_in_frontend_or_build_arguments(self):
        services = self.configuration()['services']
        frontend = services['frontend']
        self.assertEqual(set(frontend['environment']), {'SITE_ADDRESS'})
        self.assertEqual(frontend['environment']['SITE_ADDRESS'], 'https://203.0.113.10.sslip.io')
        self.assertEqual(frontend['build']['args'], {'VITE_ORGANIZATION_ID': 'visual-test'})
        for name in ('connector-service', 'change-service'):
            api = services[name]['environment']
            self.assertEqual(api['SPRING_SECURITY_USER_NAME'], 'tester')
            self.assertEqual(api['SPRING_SECURITY_USER_PASSWORD'], 'test-account-only')
            self.assertEqual(api['SERVER_FORWARD_HEADERS_STRATEGY'], 'framework')
        self.assertEqual(services['connector-service']['environment']['GITHUB_CONNECTION_ORGANIZATION_ID'],
                         services['change-service']['environment']['CHANGE_ORGANIZATION_ID'])
        self.assertEqual(services['connector-service']['environment']['GITHUB_CODE_LIFECYCLE_ENABLED'], 'true')
        self.assertEqual(services['connector-service']['secrets'][0]['source'], 'github_app_key')
        self.assertNotIn('secrets', frontend)

    def test_missing_required_configuration_fails_before_startup(self):
        for variable in ('SITE_ADDRESS', 'APP_PASSWORD', 'POSTGRES_PASSWORD', 'GITHUB_APP_ID',
                         'GITHUB_APP_SLUG', 'GITHUB_WEBHOOK_SECRET', 'GITHUB_INSTALLATION_STATE_SECRET',
                         'GITHUB_CONNECTION_ACCOUNT_ID', 'GITHUB_APP_PRIVATE_KEY_FILE'):
            with self.subTest(variable=variable):
                result = self.resolve(missing=variable)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(variable, result.stderr)


if __name__ == '__main__':
    unittest.main()
