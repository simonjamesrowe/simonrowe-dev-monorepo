#!/bin/sh
# Entrypoint for the software-factory image, which runs as both `software-factory` and `deployer`.
#
# Uses the AOT cache trained at image build time (see Dockerfile.software-factory) unless the
# container asks for class sharing to be configured some other way. The JVM refuses to start at
# all when -XX:AOTCache meets any -Xshare option, and the deployer carries
# JAVA_TOOL_OPTIONS=-Xshare:off, which cannot be changed without holding back every deploy (it is
# outside the recreate allowlist). Deciding here keeps that container, and any older compose file
# carrying the same flag, starting rather than crash-looping.
set -eu

JAR=/app/extracted/software-factory.jar
CACHE=/app/app.aot

case " ${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-} " in
  *" -Xshare:"*)
    exec java -jar "$JAR" "$@"
    ;;
esac

exec java -XX:AOTCache="$CACHE" -jar "$JAR" "$@"
