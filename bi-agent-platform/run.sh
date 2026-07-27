#!/usr/bin/env bash
# 启动 Phase 0 骨架（SB3.4 + Java 21 + Sa-Token）。
#
# 注意：本沙箱环境下 application.yml 的 server.port 偶发被运行环境覆盖，
# 因此显式传入 --server.port=8080 以保证端口稳定。
# 在标准环境（如面试官本地）可直接 `java -jar target/agent-bi-platform.jar`。
set -e
cd "$(dirname "$0")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
  JAVA_BIN="/d/st/java/jdk-21/bin/java.exe"
fi
exec "$JAVA_BIN" -jar target/agent-bi-platform.jar --server.port=8080 "$@"
