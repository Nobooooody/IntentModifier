{
  description = "Dev shell flake for IntentModifier";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.android_sdk.accept_license = true;
          config.allowUnfree = true;
        };
        androidSdk = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "36.0.0" ];
          platformVersions = [ "36.1" ];
          includeNDK = false;
          includeEmulator = false;
          includeSources = false;
        };
      in
      {
        devShells = {
          default = pkgs.mkShell {
            buildInputs = [
              androidSdk.androidsdk
              pkgs.gettext
              pkgs.python3
              pkgs.jdk17
            ];
            ANDROID_SDK_ROOT = "${androidSdk.androidsdk}/libexec/android-sdk";
            ANDROID_HOME = "${androidSdk.androidsdk}/libexec/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";
            shellHook = ''
              mkdir -p $HOME/.android
              touch $HOME/.android/repositories.cfg
              echo sdk.dir=$ANDROID_SDK_ROOT > local.properties
            '';
          };
        };
      }
    );
}
