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

# Grants two things Java 25 otherwise warns about on stderr at every start, once each (SIM-73,
# SIM-74): grpc-netty-shaded loading its native transport through System::loadLibrary, and
# protobuf reading arrays through sun.misc.Unsafe. Both are the libraries working as designed and
# both calls succeed without the flags; the flags grant explicitly what Java 25 already allows and
# warns about.
# Dockerfile.software-factory trains the AOT cache with the same two flags, so the cache and the
# runtime agree. Keep the two lists identical.
JVM_PERMISSIONS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

case " ${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-} " in
  *" -Xshare:"*)
    # shellcheck disable=SC2086 # JVM_PERMISSIONS is two words on purpose
    exec java $JVM_PERMISSIONS -jar "$JAR" "$@"
    ;;
esac

# shellcheck disable=SC2086 # JVM_PERMISSIONS is two words on purpose
exec java $JVM_PERMISSIONS -XX:AOTCache="$CACHE" -jar "$JAR" "$@"
