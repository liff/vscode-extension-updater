{
  description = "Updater for VSCode extensions";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    sbt.url = "github:zaninime/sbt-derivation";
    sbt.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, devshell, flake-utils, sbt, nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ devshell.overlay ];
        };
        jdk = pkgs.openjdk17;
        pname = "vscode-extension-updater";
      in rec {
        packages.default = sbt.lib.mkSbtDerivation {
          inherit pkgs;

          inherit pname;
          version = "1";

          depsSha256 = "sha256-z5BmcxCtNGh93TbV6itQuJbMz4rF/LmZyVMJAzg8rWU=";

          src = ./.;

          buildPhase = ''
            runHook preBuild
            sbt stage
            runHook postBuild
          '';

          installPhase = ''
            runHook preInstall
            mkdir -p "$out/"
            cp -a target/universal/stage/* "$out/"
            substituteInPlace "$out/bin/${pname}" \
                --replace '@OUT@' "$out" \
                --replace '@SHELL@' "${pkgs.runtimeShell}" \
                --replace '@JAVA@' "${jdk}/bin/java"
            runHook postInstall
          '';
        };

        apps.default = flake-utils.lib.mkApp { drv = packages.default; };

        devShell =
          pkgs.devshell.mkShell {
            packages = with pkgs; [
              (pkgs.sbt.override { jre = jdk; })
              dotty
            ];

            env = [ { name = "JAVA_HOME"; value = jdk.home; } ];
          };
      });
}
