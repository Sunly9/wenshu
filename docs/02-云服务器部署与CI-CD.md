# 云服务器部署方案与 CI/CD

> 结论：**重的东西全部搬到云服务器，本地只留"写代码 + 跑单测"。**
> 这样本地 C 盘 15.8G、内存 16G、无独显的限制全部绕过。

---

## 一、为什么必须把构建和运行分开

一个很常见的错误做法：在 2C4G 的服务器上直接 `mvn package` + `npm run build`。

- Maven 编译 Spring Boot 默认堆内存 1G 起，npm build 也要 1G 以上
- 2 核机器上编译一次 5-10 分钟，期间 CPU 打满，网站卡死
- 极容易 OOM，把正在运行的数据库一起拖崩

所以正确分工是：

| 环节 | 在哪做 | 做什么 |
|---|---|---|
| 开发 | 本地 IDEA / VS Code | 写代码、跑单元测试、前端 `npm run dev` 热更新 |
| 构建 | GitHub Actions（免费） | `mvn package`、`npm run build`、打 Docker 镜像 |
| 存储 | GHCR 镜像仓库（免费） | 托管构建产物 |
| 运行 | 云服务器（2C4G） | `docker compose pull && up -d`，只负责跑 |

**顺带收益**：你简历上就有了"CI/CD 自动化部署"这条，比单纯写"会 Docker"含金量高得多。

---

## 二、服务器怎么选

| 方案 | 配置 | 适合度 | 说明 |
|---|---|---|---|
| 腾讯云 / 阿里云轻量 | 2C4G，Ubuntu 22.04 | ⭐ 推荐 | 学生认证有优惠，国内访问快，最省心 |
| 腾讯云 / 阿里云轻量 | 2C2G | 可用但紧张 | 必须配 2G swap，索引大仓库时会慢 |
| Oracle Cloud 永久免费 | ARM 4C24G | 性能最好 | 注册容易失败、实例可能被回收，别放主要演示 |
| Railway / Render | 免费层 | 只适合长期挂机 | 数据库免费层有时限，冷启动慢，不适合当简历主演示 |

**磁盘**：40G 起步。镜像约 3G + 数据库数据 + 日志。

**关于备案**：中国大陆服务器上用域名走 80/443 需要 ICP 备案，周期 1-3 周。**最省事的做法是用 `http://公网IP:8080` 直接演示**，不涉及备案，简历上贴这个链接完全够用。想用域名就得提前开始备案，别等投简历前一周才动手。

---

## 三、首次部署流程

### 1. 初始化服务器

```bash
scp deploy/init-server.sh root@<公网IP>:/root/
ssh root@<公网IP>
bash /root/init-server.sh
```

脚本会完成：装 Docker + Compose、配国内镜像加速、创建 2G swap、配 ufw 防火墙、建 `/opt/repomind` 目录。

### 2. 上传运行配置

```bash
cd /opt/repomind
# 从本地 scp 上去，或直接在服务器上新建
cp .env.example .env
vi .env      # 填 IMAGE_PREFIX、数据库密码、LLM_API_KEY
```

`.env` 只存在于服务器，**永远不进 Git**（`.gitignore` 已屏蔽）。

### 3. 配置 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions：

| Secret | 说明 |
|---|---|
| `SSH_HOST` | 服务器公网 IP |
| `SSH_USER` | 通常 `root` |
| `SSH_KEY` | 部署用私钥（建议单独生成一对，别用你日常的） |
| `SSH_PORT` | 非 22 才需要填 |
| `GHCR_TOKEN` | 仅当镜像设为私有才需要，公开镜像可留空 |
| `GHCR_USER` | 同上 |

### 4. 推送即部署

```bash
git push origin main
```

Actions 自动构建三个镜像 → 推 GHCR → SSH 上服务器拉取重启。整个流程约 2-4 分钟。

---

## 四、日常迭代流程

```bash
# 本地：改代码、跑测试
./mvnw test
npm run test

git commit -m "feat: 支持按函数切分代码块"
git push            # 剩下的交给流水线
```

想看日志：`docker compose -f docker-compose.prod.yml logs -f backend`

---

## 五、2C4G 内存预算（务必按这个配）

| 服务 | 限制 | 说明 |
|---|---|---|
| postgres | 700m | 向量索引构建时最吃内存 |
| backend | 900m | 已设 `-Xmx640m`，不要调大 |
| embedding | 700m | bge-small ONNX 跑 CPU |
| redis | 300m | 已设 `maxmemory 256mb` + LRU 淘汰 |
| web (nginx) | 128m | 静态资源，几乎不占内存 |
| 系统 + Docker | ~400m | 守护进程、日志 |
| **合计** | **约 3.1G** | 4G 有余量；2G 必须靠 swap |

如果只有 2G 内存：把 postgres 降到 400m、backend 降到 600m，并且**先用小仓库做演示**（例如 5000 行以内的项目）。

---

## 六、安全与防刷（这条最容易被忽略，但会真金白银地亏）

公开的 demo 站点 = 公开的 API Key 消耗口。有人写个脚本就能把你的余额刷光。

必做的三件事：

1. **接口限流**：用 Redis 做令牌桶，按 IP 限每日提问次数（`.env` 里的 `APP_RATE_LIMIT_PER_IP`）
2. **单次成本上限**：限制单次回答的 `max_tokens`，限制单次索引入库的文件数与总行数
3. **别把数据库端口暴露到公网**：compose 里 postgres / redis 都只在内部网络，没有 `ports` 映射，这点已经做对了

另外生成一对专用的 SSH 密钥给部署用，别用你日常登录的私钥。

---

## 七、几个会踩的坑

- **GHCR 路径必须全小写**：`ghcr.io/YourName/Repo` 会被拒绝，改成 `ghcr.io/yourname/repo`
- **SSE 流式输出被缓冲**：nginx 必须 `proxy_buffering off`，否则回答会攒到最后一次性弹出（`deploy/nginx/default.conf` 已处理）
- **容器时区**：默认 UTC，日志和统计会差 8 小时，backend 加 `TZ=Asia/Shanghai`
- **首次拉 embedding 模型慢**：模型权重建议打进镜像，别在容器启动时下载
- **改了 `.env` 不生效**：`docker compose up -d` 不会重建容器，要加 `--force-recreate`

---

## 八、这套东西在面试里能讲什么

- 为什么构建放 CI 而不是服务器（资源隔离、可复现、避免生产机编译）
- 三个镜像怎么拆分、为什么数据库单独一层
- 2C4G 上怎么做内存预算和 OOM 防护（swap、mem_limit、GC 选型）
- SSE 为什么必须关 nginx 缓冲
- 公开服务的成本控制与防刷设计

这几条任意挑两条都能撑起一个完整的技术问答回合，比"我用过 Docker"强太多。
