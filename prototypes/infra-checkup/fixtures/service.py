#!/usr/bin/env python3
# 黄金用例靶场：代码文件（只应报带引号的长字面量）

# 合规写法：环境变量，不报
import os
DB_PASS = os.environ.get("DB_PASSWORD")

# 合规写法：变量引用/短值，不报
cache_key = token

# 违规：带引号的长字面量硬编码，P1
LEGACY_TOKEN = "sk-live-9f8e7d6c5b4a3210fedcba9876543210"
FALLBACK_SECRET = "hardcoded_fallback_secret_value"

# 合规：占位符字面量，不报
EXAMPLE_API_KEY = "your_api_key_here"

# JDBC 内嵌口令，P0（跨文件类型都判）
JDBC_URL = "jdbc:mysql://prod-db.internal:3306/appdb"
CONN = "jdbc:mysql://user:EmbeddedJdbcPass123@prod-db.internal:3306/appdb"
