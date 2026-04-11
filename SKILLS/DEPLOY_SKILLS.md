# Ragent 生产部署手册

> 从零到上线的完整记录，渐进式披露，按需查阅。
> 基于实战踩坑整理，2026-03-19 首次完成部署。

---

## 架构总览

```
【用户浏览器】
      │ HTTP 80
      ▼
【云服务器 2C4G - 腾讯云轻量】101.42.96.96
├── Nginx          → 反向代理，托管前端静态文件
├── Spring Boot    → 后端 API，端口 9090
├── MySQL:3306     → 业务数据库（Docker）
├── Redis:6379     → 缓存（Docker）
├── Milvus:19530   → 向量数据库（Docker Compose）
├── RustFS:9000    → 对象存储（Docker Compose）
└── etcd           → Milvus 依赖（Docker Compose）
```

**所有中间件端口只绑定 127.0.0.1，不对公网暴露。**

---

## 一、购买服务器

### 推荐配置
- 腾讯云轻量应用服务器，2核4G5M，60GB SSD
- 镜像：**Ubuntu22.04-Docker26**（预装 Docker，省去安装步骤）
- ⚠️ 不要选 Windows Server，兼容性差且浪费内存

### 地域选择
选离自己最近的地域，延迟更低。

---

## 二、首次连接服务器

```bash
# Windows PowerShell / macOS Terminal
ssh ubuntu@<服务器IP>
# 腾讯云默认禁用 root 直接登录，用 ubuntu 账号

# 第一件事：改密码
passwd
```

### 系统更新（遇到交互弹窗的处理）

```bash
sudo apt update && sudo apt upgrade -y
```

弹窗处理：
- **kdump-tools 配置冲突** → 选 "keep the local version currently installed"
- **Pending kernel upgrade** → 直接 Ok
- **Daemons using outdated libraries** → 保持默认勾选，直接 Ok

更新完成后重启：
```bash
sudo reboot
# 等待约 1 分钟后重新 SSH 连接
```

---

## 三、部署中间件

### 3.1 MySQL + Redis（单条命令）

```bash
# Redis
sudo docker run -d \
  --name redis \
  --restart always \
  -p 127.0.0.1:6379:6379 \
  redis:7 redis-server --requirepass 123456

# MySQL（注意：密码和数据库名与项目配置一致）
sudo docker run -d \
  --name mysql \
  --restart always \
  -p 127.0.0.1:3306:3306 \
  -e MYSQL_ROOT_PASSWORD=cr \
  -e MYSQL_DATABASE=ragent \
  mysql:8
```

> 💡 端口绑定 `127.0.0.1` 而非 `0.0.0.0`，只有本机可访问，安全。

### 3.2 Milvus + RustFS + etcd（Docker Compose）

使用项目内 `resources/docker/lightweight/` 目录下的 compose 文件，但需修改两处：

1. 端口绑定加 `127.0.0.1:`（安全）
2. Milvus 内存限制从 3G 改为 1536M（适配 4G 服务器）
3. 去掉 attu 管理界面（节省 256M）

**上传 compose 文件到服务器：**
```bash
# 本地 PowerShell 执行
scp -r "E:\...\resources\docker" ubuntu@<IP>:/home/ubuntu/ragent/
```

**创建修改后的生产版 compose：**
```bash
sudo tee /home/ubuntu/ragent/docker/milvus-prod.compose.yaml > /dev/null << 'EOF'
name: milvus-stack
services:
  rustfs:
    container_name: rustfs
    image: rustfs/rustfs:1.0.0-alpha.72
    command:
      - "--address"
      - ":9000"
      - "--console-enable"
      - "--access-key"
      - "rustfsadmin"
      - "--secret-key"
      - "rustfsadmin"
      - "/data"
    ports:
      - "127.0.0.1:9000:9000"
      - "127.0.0.1:9001:9001"
    volumes:
      - rustfs-data:/data
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 256M

  etcd:
    container_name: etcd
    image: quay.io/coreos/etcd:v3.5.18
    command: >
      etcd
      -advertise-client-urls=http://etcd:2379
      -listen-client-urls http://0.0.0.0:2379
      --data-dir /etcd
    volumes:
      - etcd-data:/etcd
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 256M

  standalone:
    container_name: milvus-standalone
    image: milvusdb/milvus:v2.6.6
    command: ["milvus", "run", "standalone"]
    security_opt:
      - seccomp:unconfined
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: rustfs:9000
      MINIO_ACCESS_KEY_ID: rustfsadmin
      MINIO_SECRET_ACCESS_KEY: rustfsadmin
    volumes:
      - milvus-data:/var/lib/milvus
    ports:
      - "127.0.0.1:19530:19530"
      - "127.0.0.1:9091:9091"
    depends_on:
      - etcd
      - rustfs
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 1536M

volumes:
  rustfs-data:
  etcd-data:
  milvus-data:

networks:
  default:
    name: milvus-net
EOF
```

**启动：**
```bash
cd /home/ubuntu/ragent/docker
sudo docker compose -f milvus-prod.compose.yaml up -d

# 验证（应看到 rustfs、etcd、milvus-standalone 均 Up）
sudo docker ps
```

> ⏳ Milvus 启动需要约 30-60 秒才真正就绪，不要立刻启动 Spring Boot。

---

## 四、初始化数据库

Milvus 启动的同时，用 Navicat 初始化 MySQL（SSH 隧道连接）：

**Navicat SSH 隧道配置：**
- SSH Host：服务器IP，端口22，用户名ubuntu
- 数据库 Host：127.0.0.1，端口3306，用户名root

**执行 SQL（顺序不能错）：**
1. 先执行 `resources/database/schema_table.sql`
2. 再执行 `resources/database/init_data.sql`

**验证：**
```sql
USE ragent;
SHOW TABLES;       -- 应看到 20 张表
SELECT * FROM t_user;  -- 应看到 admin 账号
```

---

## 五、部署后端

### 5.1 本地打包

```bash
# 项目根目录执行
./mvnw clean package -DskipTests -pl bootstrap -am

# 确认 jar 包
dir bootstrap\target\*.jar
# → bootstrap-0.0.1-SNAPSHOT.jar
```

### 5.2 上传到服务器

```bash
# 本地 PowerShell
scp bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/home/ubuntu/ragent/
```

### 5.3 安装 Java 21

```bash
sudo apt install openjdk-21-jdk -y
java -version
```

### 5.4 创建启动脚本

```bash
cat > /home/ubuntu/ragent/start.sh << 'EOF'
#!/bin/bash
nohup java -jar /home/ubuntu/ragent/bootstrap-0.0.1-SNAPSHOT.jar \
  >> /home/ubuntu/ragent/app.log 2>&1 &
echo $! > /home/ubuntu/ragent/app.pid
echo "启动成功，PID: $(cat /home/ubuntu/ragent/app.pid)"
EOF

chmod +x /home/ubuntu/ragent/start.sh
```

### 5.5 启动并查看日志

```bash
cd /home/ubuntu/ragent
./start.sh
tail -f app.log

# 成功标志：
# Started RagentApplication in xx seconds
```

> ℹ️ MCP Server 连接失败的 ERROR 日志是正常的，系统会自动降级运行。

---

## 六、部署前端

### 6.1 本地打包（React + Vite）

```bash
cd frontend
npm run build
# 产物在 frontend/dist/ 目录
```

### 6.2 上传 dist 到服务器

```bash
scp -r "E:\...\frontend\dist" ubuntu@<IP>:/home/ubuntu/ragent/frontend
```

### 6.3 配置 Nginx

```bash
sudo tee /etc/nginx/sites-available/ragent > /dev/null << 'EOF'
server {
    listen 80;
    server_name <服务器IP>;

    location / {
        root /home/ubuntu/ragent/frontend;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:9090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/ragent/sse {
        proxy_pass http://127.0.0.1:9090;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/ragent /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx


```

### 6.4 修复权限（必须）

```bash
# Nginx 以 www-data 运行，需要 /home/ubuntu 目录有执行权限
sudo chmod o+x /home/ubuntu
sudo chmod -R o+r /home/ubuntu/ragent/frontend
sudo systemctl reload nginx
```

---

## 七、验证部署

浏览器访问 `http://<服务器IP>`，看到登录页面后：

```
用户名：admin
密码：admin
```

---

## 八、待完成的后续任务（优先级排序）

### ⚡ 高优先级

**1. 配置 Spring Boot 开机自启**（已完成，不过我配置了 systemd 服务，暂时关闭了自动启动）

服务器重启后 Spring Boot 不会自动启动，需要配置 systemd 服务：

```bash
sudo tee /etc/systemd/system/ragent.service > /dev/null << 'EOF'
[Unit]
Description=Ragent Spring Boot Application
After=network.target docker.service

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/ragent
ExecStart=/usr/bin/java -jar /home/ubuntu/ragent/bootstrap-0.0.1-SNAPSHOT.jar
ExecStop=/bin/kill -15 $MAINPID
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/home/ubuntu/ragent/app1.log
StandardError=append:/home/ubuntu/ragent/app1.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable ragent
sudo systemctl start ragent
```

**2. 初始化 RustFS Bucket**

访问 `http://<服务器IP>:9001`（需临时开放端口或 SSH 隧道），用 `rustfsadmin/rustfsadmin` 登录，创建名为 `a-bucket` 的存储桶。

**3. API Key 改为环境变量注入**

避免密钥硬编码在配置文件中：

```bash
# 在 start.sh 或 systemd 配置中注入
export BAILIAN_API_KEY=sk-xxxxx
export SILICONFLOW_API_KEY=sk-xxxxx
```

### 🔧 中优先级

**4. 配置 HTTPS**

申请域名 + Let's Encrypt 免费证书，用 Certbot 自动配置：

```bash
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d yourdomain.com
```

**5. 导师访问账号**

在系统中为导师创建只读账号，并预设常用问题模板。

### 📊 低优先级

**6. 定期备份 MySQL 数据**

```bash
# 创建每日备份脚本
sudo docker exec mysql mysqldump -uroot -pcr ragent > /home/ubuntu/backup/ragent_$(date +%Y%m%d).sql
```

---

## 踩坑记录

| 坑 | 原因 | 解决方案 |
|----|------|----------|
| Milvus 启动报 nil pointer | 直接 `docker run` 不带 etcd/RustFS | 必须用 Docker Compose 整体启动 |
| Milvus 连接 etcd 超时 | 缺少嵌入式 etcd 环境变量 | 用 compose 文件，内含正确的 ETCD_ENDPOINTS |
| Spring Boot 启动失败 DeadlineExceeded | Milvus 还没就绪就启动了 | 等待 Milvus 日志出现 "startup successfully" 再启动 |
| 浏览器访问 500 Internal Server Error | Nginx 无权限读取 /home/ubuntu | `sudo chmod o+x /home/ubuntu` |
| SSH 登录 root 被拒 | 腾讯云默认禁用 root 直接登录 | 用 ubuntu 账号，命令加 sudo |
| Windows 终端粘贴配置文件困难 | 换行符/编码问题 | 改用 `tee + EOF` 方式直接生成文件 |
| 4G 服务器跑不下 Milvus | 原始配置限制 3G，剩余不够其他服务 | 降至 1536M，去掉 attu，实测稳定 |
| npm run build 在服务器报错 | 误在服务器上执行了前端构建命令 | 前端 build 在本地执行，只上传 dist 产物 |
| Redis Desktop 连接云服务器失败 | Redis 绑定 127.0.0.1 不对外暴露（正确的安全配置） | 用 `ssh -L 6390:127.0.0.1:6379 ubuntu@<IP> -N` 建本地隧道，Desktop 连 127.0.0.1:6390，SSH 窗口挂着不动是正常的 |
| 网上文章教改 Redis bind 配置 | 直接暴露 Redis 到公网，任何人可连接 | 坚决不做，用 SSH 隧道代替 |

---

## 常用运维命令

```bash
# 查看所有容器状态
sudo docker ps

# 查看内存占用
sudo docker stats --no-stream
free -h

# 查看 Spring Boot 日志
tail -f /home/ubuntu/ragent/app.log

# 重启 Spring Boot
kill $(cat /home/ubuntu/ragent/app.pid)
cd /home/ubuntu/ragent && ./start.sh

# 重启 Milvus 全家桶
cd /home/ubuntu/ragent/docker
sudo docker compose -f milvus-prod.compose.yaml restart

# Navicat 连接远程 MySQL（SSH 隧道）
# SSH: ubuntu@<IP>:22
# DB:  127.0.0.1:3306  root/cr

# Redis Desktop 连接（SSH 命令行隧道方式）
# 步骤1：本地开一个 PowerShell 窗口，执行以下命令并保持挂着不动：
#   ssh -L 6390:127.0.0.1:6379 ubuntu@<IP> -N
# 步骤2：Redis Desktop 新建连接，不开 SSH Tunnel，直接填：
#   Host: 127.0.0.1
#   Port: 6390   ← 注意不是 6379
#   Auth: 123456
# ⚠️ 不要按网上文章修改 Redis bind 配置暴露公网端口，非常危险
```
