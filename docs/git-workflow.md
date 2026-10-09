# 提交与分支同步规范（Git Workflow SOP）

> 适用范围：firestar-service 每次需要把 `develop` 的改动同步到受保护分支（`test` / `master` / `main`）时。
> 制定背景：参考 be-media-man-system 在 9/9 全量合并时暴露「无检查点、全量自动合并」的风险，本 SOP 改为**分步、可中断**，并在 `test` 之后设**人工确认点**。
> 本文件是本项目提交操作的**唯一权威 SOP**，HANDOFF.md 仅作引用。

---

## 分支模型

- `develop`：日常开发主线，所有改动先合入这里。
- `test`：测试环境对应分支。
- `master`：预发/生产主干（保留）。
- `main`：另一条主干（历史保留，当前与 `master` 同指）。

---

## 标准流程（6 步，严格按顺序执行）

### 步骤 1 · 提交 develop 并推送到远程

```bash
git add <本次明确改动的文件>     # 只加本次改动，禁止 git add -A
git commit -m "类型: 简述"
git push origin develop
```

- **纪律**：只提交本次明确改动的**跟踪文件**，**禁止 `git add -A` 全量添加**（避免误带未跟踪/被忽略文件，如 `.env/*`、`.probe*`、`.temp/*`）。
- 提交信息遵循 Conventional Commits（`fix:` / `feat:` / `docs:` / `refactor:` …）。
- 提交前先 `git status` 确认工作树无意外删除/改动。

### 步骤 2 · 合并到 test

```bash
git checkout test
git merge develop --no-edit
git push origin test
```

### 步骤 3 · 确认是否继续（⚠️ 人工检查点）

- **必须在此停顿，询问用户**是否继续合并剩余分支（`master` + `main`）。
- 用户**不同意** → 直接 `git checkout develop`，流程结束，等待后续任务。
- 用户**同意** → 继续步骤 4。

### 步骤 4 · 合并到 master 并推送

```bash
git checkout master
git merge develop --no-edit
git push origin master
```

### 步骤 5 · 合并到 main 并推送

```bash
git checkout main
git merge develop --no-edit
git push origin main
```

### 步骤 6 · 切回 develop 并核验交付状态（⚠️ 强制收尾自检）

```bash
git fetch
git checkout develop
git status
```

- **交付标准（硬性）**：`git status` 须显示 `nothing to commit, working tree clean` 且**无 `upstream is gone` 提示**。
- **环境要求（关键）**：本 SOP 所有 `git` 命令须使用**物理机系统安装的 git**，**禁止 IDE / WorkBuddy 自带的便携版 git**。
- 等待后续任务。

---

## 关键纪律

1. **合并顺序固定**：`develop → test →（确认）→ master → main → develop`。不得跳步、不得调换。
2. **每步切换前后跑 `git status`**：捕获工作树被安全删除壳意外移除，提交前务必确认无意外删除。
3. **只 Fast-forward / `--no-edit` 合并**：四分支同源，不产生多余 merge commit。
4. **绝不 `git clean -fdx`**：`-x` 会清掉 `.env/` 等被忽略的本地凭据。
5. **未跟踪/被忽略文件不纳入提交**：`.env/*`、`.probe*`、`.temp/*` 永远不进库。
6. **远程走 SSH / 已存 HTTPS 凭据**：本机 HTTPS 凭证已通过 GCM 存入 Windows 凭据管理器（firestar 仓库）。
7. **步骤 6 强制 `git fetch` + 完整 `git status` 收尾自检**。
8. **严格使用物理机系统安装的 git**：
   - **Windows**：`C:\Program Files\Git\cmd\git.exe`（Git for Windows 官方安装版）。
   - **禁止**使用 IDE / WorkBuddy / 其他工具自带的便携版 git（如 `/mingw64/bin/git`、PortableGit）。

---

## 提交信息约定

| 类型 | 含义 |
|---|---|
| `feat:` | 新功能 |
| `fix:` | 缺陷修复 |
| `docs:` | 文档（含本 SOP） |
| `refactor:` | 重构（无行为变化） |
| `chore:` | 杂项（不进库的构建/配置微调） |

---

## 反例（禁止的做法）

- ❌ 一次性全量合并再统一 push —— 无检查点，出问题难止血。
- ❌ `git add -A` / `git add .` 全量暂存 —— 会捎上 `.env/` 真值、探针、临时文件。
- ❌ 跳过步骤 3 的确认直接合 `master`/`main`。
- ❌ `git clean -fdx` 清整个工作树 —— 删 `.env/`，服务起不来。
