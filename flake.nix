{
  description = "Dev shell flake for IntentModifier";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs?rev=b5aa0fbd538984f6e3d201be0005b4463d8b09f8";
  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.android_sdk.accept_license = true;
          config.allowUnfree = true;
          overlays = [ self.overlays.default ];
        };
      in {
        devShells = {
          default = pkgs.intent-modifier.sdk.shell;
          noAS =
            pkgs.intent-modifier.sdk.shell.override { androidStudio = null; };
        };
      }) // {
        overlays.default = final: prev: {
          intent-modifier = {
            sdk = final.lib.makeExtensible (self: {

              # Update versions here
              buildToolsVersion = "36.0.0";
              platformToolsVersion = "36.0.2";
              platformVersion = "36";
              ndkVersion = "28.0.13004108";

              includeNDK = false;
              androidComposition = final.androidenv.composeAndroidPackages {
                inherit (self) platformToolsVersion ndkVersion includeNDK;
                buildToolsVersions = [ self.buildToolsVersion ];
                platformVersions = [ self.platformVersion ];
                includeEmulator = false;
                includeSources = false;
              };

              shell = final.lib.makeOverridable
                ({ androidStudio, generateLocalProperties }:
                  with final;
                  with self;
                  mkShell rec {
                    buildInputs = [
                      androidComposition.androidsdk
                      gettext
                      python3
                      jdk17
                      androidStudio
                    ];
                    ANDROID_SDK_ROOT =
                      "${androidComposition.androidsdk}/libexec/android-sdk";
                    ANDROID_HOME = ANDROID_SDK_ROOT;
                    BUILD_TOOLS_VERSION = buildToolsVersion;
                    GRADLE_OPTS =
                      "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidComposition.androidsdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
                    JAVA_HOME = "${jdk17}";
                    shellHook = lib.optionalString generateLocalProperties ''
                      echo sdk.dir=$ANDROID_SDK_ROOT > local.properties
                    '';
                  }) {
                    androidStudio = final.androidStudioPackages.stable;
                    generateLocalProperties = true;
                  };
            });
          };
        };
      };
}
