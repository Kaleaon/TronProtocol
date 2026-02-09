#!/usr/bin/env python3
"""
TronProtocol YAML Build Configurator
Parses YAML configuration and generates/updates Gradle build files
"""

import os
import sys
import yaml
import argparse
import difflib
from pathlib import Path


class BuildConfigurator:
    """Parse YAML config and apply to Gradle build files"""

    def __init__(self, config_file, yaml_format='auto'):
        self.config_file = Path(config_file)
        self.project_root = self.config_file.parent
        self.yaml_format = yaml_format
        self.config = None

    def load_config(self):
        """Load and parse YAML configuration"""
        if not self.config_file.exists():
            raise FileNotFoundError(f"Configuration file not found: {self.config_file}")

        with open(self.config_file, 'r') as f:
            self.config = yaml.safe_load(f)

        # Auto-detect format
        if self.yaml_format == 'auto':
            if 'app' in self.config and 'build' in self.config:
                self.yaml_format = 'toolneuron'
            elif 'project' in self.config and 'variants' in self.config:
                self.yaml_format = 'cleverferret'
            else:
                raise ValueError("Unknown YAML format")

        print(f"[INFO] Loaded configuration from {self.config_file} (format: {self.yaml_format})")
        return self.config

    def get_app_config(self):
        """Extract app configuration from YAML"""
        if self.yaml_format == 'toolneuron':
            return {
                'name': self.config.get('app', {}).get('name', 'TronProtocol'),
                'package': self.config.get('app', {}).get('package', 'com.tronprotocol.app'),
                'version_code': self.config.get('version', {}).get('code', 1),
                'version_name': self.config.get('version', {}).get('name', '1.0'),
                'compile_sdk': self.config.get('build', {}).get('compile_sdk', 34),
                'min_sdk': self.config.get('build', {}).get('min_sdk', 24),
                'target_sdk': self.config.get('build', {}).get('target_sdk', 34),
            }
        else:  # cleverferret
            return {
                'name': self.config.get('project', {}).get('name', 'TronProtocol'),
                'package': self.config.get('project', {}).get('id', 'com.tronprotocol.app'),
                'version_code': self.config.get('versioning', {}).get('version_code', 1),
                'version_name': self.config.get('versioning', {}).get('version_name', '1.0'),
                'compile_sdk': self.config.get('android', {}).get('compileSdk', 34),
                'min_sdk': self.config.get('android', {}).get('minSdk', 24),
                'target_sdk': self.config.get('android', {}).get('targetSdk', 34),
            }

    def get_build_types(self):
        """Extract build type configurations"""
        if self.yaml_format == 'toolneuron':
            return self.config.get('build_types', {})
        else:  # cleverferret
            build_types = {}
            for variant in self.config.get('variants', []):
                variant_name = variant.get('name')
                variant_config = variant.get('config', {})
                build_types[variant_name] = {
                    'enabled': True,
                    'debuggable': variant_config.get('debuggable', False),
                    'minify_enabled': variant_config.get('minifyEnabled', False),
                    'shrink_resources': variant_config.get('shrinkResources', False),
                    'output_name': variant.get('output', {}).get('name', f'TronProtocol-{variant_name}'),
                }
            return build_types

    def get_dependencies(self):
        """Extract dependency list"""
        if self.yaml_format == 'toolneuron':
            deps = self.config.get('dependencies', [])
            return [self._validate_dependency_coordinate(dep) for dep in deps]

        deps = []
        libraries = self.config.get('libraries', {})

        # Process androidx dependencies
        for dep in libraries.get('androidx', []):
            artifact, version = self._parse_name_version(dep, "libraries.androidx")
            deps.append(self._convert_androidx_dependency(artifact, version))

        # Process google services
        for dep in libraries.get('google_services', []):
            artifact, version = self._parse_name_version(dep, "libraries.google_services")
            deps.append(f'com.google.android.gms:{artifact}:{version}')

        # Process tensorflow
        for dep in libraries.get('tensorflow', []):
            artifact, version = self._parse_name_version(dep, "libraries.tensorflow")
            deps.append(f'org.tensorflow:{artifact}:{version}')

        return deps

    @staticmethod
    def _parse_name_version(entry, source):
        """Parse dependency entry in `name:version` form with validation."""
        if not isinstance(entry, str):
            raise ValueError(f"Malformed dependency in {source}: expected string, got {type(entry).__name__}")

        parts = entry.split(':')
        if len(parts) != 2:
            raise ValueError(
                f"Malformed dependency in {source}: '{entry}' (expected 'name:version')"
            )

        name, version = (part.strip() for part in parts)
        if not name or not version:
            raise ValueError(
                f"Malformed dependency in {source}: '{entry}' (name and version must be non-empty)"
            )
        return name, version

    @staticmethod
    def _validate_dependency_coordinate(dep):
        """Validate `group:artifact:version` dependency coordinates."""
        if not isinstance(dep, str):
            raise ValueError(f"Malformed dependency coordinate: expected string, got {type(dep).__name__}")

        parts = dep.split(':')
        if len(parts) != 3 or any(not part.strip() for part in parts):
            raise ValueError(
                f"Malformed dependency coordinate '{dep}' (expected 'group:artifact:version')"
            )
        return dep

    @staticmethod
    def _convert_androidx_dependency(artifact, version):
        """Convert cleverferret androidx shorthand to full Maven coordinates."""
        special_cases = {
            'material': 'com.google.android.material:material',
        }

        if artifact in special_cases:
            return f"{special_cases[artifact]}:{version}"
        return f"androidx.{artifact}:{artifact}:{version}"

    def should_include_kotlin_plugin(self):
        """Include Kotlin Android plugin when Kotlin sources are present."""
        app_dir = self.project_root / 'app'
        kotlin_files = list(app_dir.glob('src/**/*.kt'))
        if kotlin_files:
            return True

        existing_build_gradle = app_dir / 'build.gradle'
        if existing_build_gradle.exists():
            content = existing_build_gradle.read_text()
            if "id 'org.jetbrains.kotlin.android'" in content:
                return True

        return False

    def generate_gradle_config(self, output_file=None):
        """Generate Gradle build configuration"""
        if output_file is None:
            output_file = self.project_root / 'app' / 'build.gradle.generated'

        app_config = self.get_app_config()
        build_types = self.get_build_types()
        dependencies = self.get_dependencies()
        include_kotlin_plugin = self.should_include_kotlin_plugin()

        gradle_content = f"""// Auto-generated from {self.config_file.name}
// Do not edit manually - changes will be overwritten

plugins {{
    id 'com.android.application'
"""

        if include_kotlin_plugin:
            gradle_content += "    id 'org.jetbrains.kotlin.android'\n"

        gradle_content += f"""}}

android {{
    namespace '{app_config['package']}'
    compileSdk {app_config['compile_sdk']}

    defaultConfig {{
        applicationId "{app_config['package']}"
        minSdk {app_config['min_sdk']}
        targetSdk {app_config['target_sdk']}
        versionCode {app_config['version_code']}
        versionName "{app_config['version_name']}"
    }}

    buildTypes {{
"""

        if not build_types:
            build_types = {'release': {'enabled': True, 'minify_enabled': False}}

        # Add build types
        for build_type, config in build_types.items():
            if config.get('enabled', True):
                gradle_content += f"""        {build_type} {{
            debuggable {str(config.get('debuggable', False)).lower()}
            minifyEnabled {str(config.get('minify_enabled', False)).lower()}
"""
                if config.get('shrink_resources'):
                    gradle_content += "            shrinkResources true\n"

                if config.get('proguard_files'):
                    files = config['proguard_files']
                    if isinstance(files, list):
                        gradle_content += "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')"
                        for pf in files:
                            gradle_content += f", '{pf}'"
                        gradle_content += "\n"
                elif build_type == 'release':
                    gradle_content += (
                        "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), "
                        "'proguard-rules.pro'\n"
                    )

                gradle_content += "        }\n"

        gradle_content += """    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
"""

        if include_kotlin_plugin:
            gradle_content += """
    kotlinOptions {
        jvmTarget = '1.8'
    }
"""

        gradle_content += """

dependencies {
"""

        # Add dependencies
        for dep in dependencies:
            gradle_content += f"    implementation '{dep}'\n"

        gradle_content += "}\n"

        # Write to file
        with open(output_file, 'w') as f:
            f.write(gradle_content)

        print(f"[SUCCESS] Generated Gradle configuration: {output_file}")
        return output_file

    def update_build_gradle(self, safe_update=False, force=False):
        """Update the actual app/build.gradle file"""
        build_gradle = self.project_root / 'app' / 'build.gradle'

        # Generate to temporary file first
        temp_file = self.generate_gradle_config()

        if safe_update and build_gradle.exists():
            current_content = build_gradle.read_text().splitlines(keepends=True)
            generated_content = temp_file.read_text().splitlines(keepends=True)
            diff = list(difflib.unified_diff(
                current_content,
                generated_content,
                fromfile=str(build_gradle),
                tofile=str(temp_file),
            ))

            if diff:
                print("[WARN] Safe update mode detected changes to app/build.gradle:")
                print(''.join(diff))
                if not force:
                    print("[WARN] Destructive replacement prevented. Re-run with --force to apply.")
                    temp_file.unlink()
                    return

        # Backup original
        backup_file = build_gradle.with_suffix('.gradle.backup')
        if build_gradle.exists():
            import shutil
            shutil.copy(build_gradle, backup_file)
            print(f"[INFO] Backed up original build.gradle to {backup_file}")

        # Copy generated to actual build.gradle
        import shutil
        shutil.copy(temp_file, build_gradle)
        print(f"[SUCCESS] Updated {build_gradle}")

        # Remove temp file
        temp_file.unlink()

    def print_summary(self):
        """Print configuration summary"""
        app_config = self.get_app_config()
        build_types = self.get_build_types()

        print("\n" + "=" * 60)
        print("TronProtocol Build Configuration Summary")
        print("=" * 60)
        print(f"App Name:      {app_config['name']}")
        print(f"Package:       {app_config['package']}")
        print(f"Version:       {app_config['version_name']} ({app_config['version_code']})")
        print(f"Compile SDK:   {app_config['compile_sdk']}")
        print(f"Min SDK:       {app_config['min_sdk']}")
        print(f"Target SDK:    {app_config['target_sdk']}")
        print(f"\nBuild Types:   {', '.join(build_types.keys())}")
        print("=" * 60 + "\n")


def run_self_check():
    """Validate dependency coordinate generation for known YAML formats."""
    expected = {
        'toolneuron.yaml': [
            'androidx.appcompat:appcompat:1.6.1',
            'com.google.android.material:material:1.9.0',
            'androidx.constraintlayout:constraintlayout:2.1.4',
            'com.google.android.gms:play-services-mlkit-text-recognition:19.0.0',
            'org.tensorflow:tensorflow-lite:2.13.0',
            'org.tensorflow:tensorflow-lite-gpu:2.13.0',
            'org.tensorflow:tensorflow-lite-support:0.4.4',
            'com.google.android.gms:play-services-base:18.2.0',
        ],
        'cleverferret.yaml': [
            'androidx.appcompat:appcompat:1.6.1',
            'com.google.android.material:material:1.9.0',
            'androidx.constraintlayout:constraintlayout:2.1.4',
            'com.google.android.gms:play-services-mlkit-text-recognition:19.0.0',
            'com.google.android.gms:play-services-base:18.2.0',
            'org.tensorflow:tensorflow-lite:2.13.0',
            'org.tensorflow:tensorflow-lite-gpu:2.13.0',
            'org.tensorflow:tensorflow-lite-support:0.4.4',
        ],
    }

    for file_name, expected_deps in expected.items():
        configurator = BuildConfigurator(file_name)
        configurator.load_config()
        actual = configurator.get_dependencies()
        if actual != expected_deps:
            raise AssertionError(
                f"Dependency self-check failed for {file_name}\nExpected: {expected_deps}\nActual:   {actual}"
            )

    print("[SUCCESS] Dependency self-check passed for toolneuron.yaml and cleverferret.yaml")


def main():
    parser = argparse.ArgumentParser(
        description='TronProtocol YAML Build Configurator',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Generate Gradle config from toolneuron.yaml
  %(prog)s -c toolneuron.yaml -g

  # Update build.gradle from cleverferret.yaml
  %(prog)s -c cleverferret.yaml -u

  # Show configuration summary
  %(prog)s -c toolneuron.yaml -s
        """
    )

    parser.add_argument('-c', '--config',
                        help='YAML configuration file')
    parser.add_argument('-f', '--format', default='auto',
                        choices=['auto', 'toolneuron', 'cleverferret'],
                        help='YAML format (default: auto-detect)')
    parser.add_argument('-g', '--generate', action='store_true',
                        help='Generate Gradle build file')
    parser.add_argument('-u', '--update', action='store_true',
                        help='Update actual build.gradle')
    parser.add_argument('-s', '--summary', action='store_true',
                        help='Print configuration summary')
    parser.add_argument('--safe-update', action='store_true',
                        help='Show diff and prevent destructive replacement unless --force is set')
    parser.add_argument('--force', action='store_true',
                        help='Apply destructive replacement (used with --safe-update)')
    parser.add_argument('--self-check', action='store_true',
                        help='Run dependency coordinate self-checks')

    args = parser.parse_args()

    try:
        if args.self_check:
            run_self_check()
            return 0

        if not args.config:
            raise ValueError('Configuration file is required unless --self-check is used')

        configurator = BuildConfigurator(args.config, args.format)
        configurator.load_config()

        if args.summary:
            configurator.print_summary()

        if args.generate:
            configurator.generate_gradle_config()

        if args.update:
            configurator.update_build_gradle(safe_update=args.safe_update, force=args.force)

        if not (args.summary or args.generate or args.update):
            configurator.print_summary()
            print("[INFO] Use -g to generate, -u to update, or -s for summary")

    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == '__main__':
    sys.exit(main())
