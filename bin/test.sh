#!/usr/bin/env bash
# 集成测试短命令门面（日常用这个就够了）
#   bash bin/test.sh                      全量，自动重建空测试库
#   bash bin/test.sh -Dtest=SmokeTest     只跑指定测试类
#   bash bin/test.sh --no-reset ...       沿用现有库快速重跑
#   bash bin/test.sh --help               帮助
exec bash "$(dirname "$0")/../scripts/run-integration-tests.sh" "$@"
