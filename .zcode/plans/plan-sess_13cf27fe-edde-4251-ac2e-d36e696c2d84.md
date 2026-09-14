创建一个一键启动脚本 `启动项目.bat`，放在项目根目录 E:\springboot\project3\，用户双击即可启动整个项目。

脚本功能：
1. 自动从 .env 文件读取 DEEPSEEK_API_KEY
2. 自动启动 Docker 容器（PostgreSQL + Redis）
3. 自动启动 Spring Boot 后端（含前端页面）
4. 自动打开浏览器访问 http://localhost:8080

用户换 Key 时只需用记事本编辑 .env 文件，重启脚本即可生效。