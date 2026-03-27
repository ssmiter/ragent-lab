"""
Ragent 检索透视镜 —— 看清每次召回到底发生了什么

用法：
  python ragent_retrieval_inspector.py "MCP协议为什么不使用HTTP或gRPC"
  python ragent_retrieval_inspector.py --compare "MCP协议" "MCP协议为什么不使用HTTP或gRPC，而是选择了JSON-RPC 2.0"
  python ragent_retrieval_inspector.py --batch questions.txt
  python ragent_retrieval_inspector.py --interactive

原理：
  直接调 Ragent 的对话接口，同时监控服务器日志，
  把「问题 → 召回chunk → 最终回答」整条链路可视化。
  
  但更有价值的方式是：直接调 Milvus 的向量检索接口，
  绕过 Ragent 的 LLM 路由/重写/意图识别，只看纯粹的向量召回。
  这样你能看到「原始问题 vs embedding空间」的真实匹配情况。
"""

import requests
import json
import sys
import os
import argparse
from datetime import datetime

# ============================================================
# 配置区
# ============================================================
RAGENT_BASE = "http://101.42.96.96/api/ragent"
AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2"
KNOWLEDGE_BASE_ID = "2035187495217422336"

# Milvus 直连配置（通过 SSH 隧道：ssh -L 19530:127.0.0.1:19530 ubuntu@101.42.96.96 -N）
MILVUS_HOST = "127.0.0.1"
MILVUS_PORT = 19530
COLLECTION_NAME = "ragentdocs"  # 从意图树日志中获取

# ============================================================
# 方式一：通过 Ragent HTTP API 对话（完整链路，含LLM）
# ============================================================

def chat_via_ragent(question, session_id=None):
    """
    通过 Ragent 的对话接口提问
    返回完整回答（需要配合服务器日志看召回详情）
    """
    session = requests.Session()
    session.headers.update({
        "Authorization": AUTH_TOKEN,
        "Accept": "application/json, text/plain, */*",
        "Content-Type": "application/json",
    })
    session.cookies.set("Authorization", AUTH_TOKEN)

    # 你需要先抓一下对话接口的URL和参数格式
    # 从浏览器F12抓对话时的请求，补充到这里
    # 下面是一个猜测的结构，你需要验证
    url = f"{RAGENT_BASE}/chat/stream"  # 需要确认实际路径
    payload = {
        "question": question,
        "sessionId": session_id,
        # 可能还有其他参数如 conversationId, deepThinking 等
    }

    print(f"\n{'='*70}")
    print(f"🔍 问题: {question}")
    print(f"{'='*70}")
    print(f"⚠️  完整链路模式：回答会包含LLM生成，召回详情需要看服务器日志")
    print(f"   建议同时运行: ssh ubuntu@101.42.96.96 'tail -f /home/ubuntu/ragent/app.log'")

    try:
        resp = session.post(url, json=payload, stream=True, timeout=120)
        resp.raise_for_status()
        # SSE 流式响应处理
        full_response = ""
        for line in resp.iter_lines(decode_unicode=True):
            if line and line.startswith("data:"):
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                    content = chunk.get("content", "") or chunk.get("data", {}).get("content", "")
                    if content:
                        full_response += content
                        print(content, end="", flush=True)
                except json.JSONDecodeError:
                    pass
        print()
        return full_response
    except Exception as e:
        print(f"❌ 请求失败: {e}")
        print(f"   你可能需要先抓一下对话接口的实际URL和参数格式")
        return None


# ============================================================
# 方式二：直接查 Milvus 向量数据库（纯检索，无LLM）
# ============================================================

def search_milvus_direct(question, top_k=20):
    """
    绕过 Ragent，直接查 Milvus 向量数据库
    需要：
    1. SSH 隧道：ssh -L 19530:127.0.0.1:19530 ubuntu@101.42.96.96 -N
    2. pip install pymilvus
    3. 需要知道 embedding 模型来把 question 转成向量
    
    这是最纯粹的检索透视 —— 你能看到向量空间里真实的距离
    """
    try:
        from pymilvus import connections, Collection
    except ImportError:
        print("❌ 需要安装 pymilvus: pip install pymilvus")
        print("   然后开 SSH 隧道: ssh -L 19530:127.0.0.1:19530 ubuntu@101.42.96.96 -N")
        return None

    try:
        connections.connect("default", host=MILVUS_HOST, port=MILVUS_PORT)
        collection = Collection(COLLECTION_NAME)
        collection.load()

        # 获取 collection 的 schema 信息
        print(f"\n📊 Collection: {COLLECTION_NAME}")
        print(f"   总记录数: {collection.num_entities}")
        print(f"   字段列表:")
        for field in collection.schema.fields:
            print(f"     - {field.name} ({field.dtype})")

        # TODO: 这里需要知道 Ragent 用的什么 embedding 模型
        # 才能把 question 转成向量进行检索
        # 从配置文件或代码里找 embedding 模型信息
        print(f"\n⚠️  直接检索需要知道 embedding 模型来向量化问题")
        print(f"   请在 Ragent 配置文件中查找 embedding 相关配置")
        print(f"   通常在 application.yml 或 EmbeddingService 相关代码中")

        connections.disconnect("default")
        return None

    except Exception as e:
        print(f"❌ Milvus 连接失败: {e}")
        print(f"   确认 SSH 隧道已开启: ssh -L 19530:127.0.0.1:19530 ubuntu@101.42.96.96 -N")
        return None


# ============================================================
# 方式三：解析服务器日志（最实用，不需要额外依赖）
# ============================================================

def parse_retrieval_log(log_text):
    """
    解析 Ragent 服务器日志中的检索信息
    你可以把 tail -f app.log 的输出粘贴进来分析
    """
    results = {
        "original_question": None,
        "rewritten_question": None,
        "sub_questions": [],
        "intent_results": [],
        "retrieval_channel": None,
        "chunk_count_before_dedup": None,
        "chunk_count_after_dedup": None,
        "chunk_count_after_rerank": None,
        "retrieval_time_ms": None,
    }

    for line in log_text.strip().split("\n"):
        if "原始问题：" in line:
            results["original_question"] = line.split("原始问题：")[1].strip()
        elif "改写结果：" in line:
            results["rewritten_question"] = line.split("改写结果：")[1].strip()
        elif "子问题：" in line:
            sub = line.split("子问题：")[1].strip()
            results["sub_questions"] = sub.strip("[]").split(", ")
        elif "意图识别树如下所示" in line:
            # 下面几行是 JSON，需要多行解析
            pass
        elif "score" in line and '"score"' in line:
            try:
                # 尝试从 JSON 片段中提取 score
                import re
                score_match = re.search(r'"score"\s*:\s*([\d.]+)', line)
                if score_match:
                    results["intent_results"].append(float(score_match.group(1)))
            except:
                pass
        elif "启用的检索通道" in line:
            if "IntentDirectedSearch" in line:
                results["retrieval_channel"] = "IntentDirectedSearch (意图定向)"
            elif "VectorGlobalSearch" in line:
                results["retrieval_channel"] = "VectorGlobalSearch (全局兜底)"
        elif "Deduplication 完成" in line:
            parts = line.split("输入: ")[1] if "输入: " in line else ""
            if parts:
                results["chunk_count_before_dedup"] = parts.split(" 个")[0]
                after = parts.split("输出: ")[1] if "输出: " in parts else ""
                results["chunk_count_after_dedup"] = after.split(" 个")[0] if after else None
        elif "Rerank 完成" in line:
            parts = line.split("输入: ")[1] if "输入: " in line else ""
            if parts:
                after = parts.split("输出: ")[1] if "输出: " in parts else ""
                results["chunk_count_after_rerank"] = after.split(" 个")[0] if after else None
        elif "耗时" in line and "检索" in line:
            import re
            time_match = re.search(r'耗时[：:]\s*(\d+)ms', line)
            if time_match:
                results["retrieval_time_ms"] = int(time_match.group(1))

    return results


def print_retrieval_report(results):
    """格式化打印检索分析报告"""
    print(f"\n{'='*70}")
    print(f"📋 检索链路分析报告")
    print(f"{'='*70}")

    print(f"\n🔤 原始问题: {results.get('original_question', '未知')}")
    print(f"✏️  改写结果: {results.get('rewritten_question', '未改写')}")

    if results.get("sub_questions"):
        print(f"🔀 子问题拆分:")
        for i, q in enumerate(results["sub_questions"], 1):
            print(f"   {i}. {q}")

    print(f"\n🎯 检索通道: {results.get('retrieval_channel', '未知')}")

    if results.get("intent_results"):
        print(f"📊 意图识别分数: {results['intent_results']}")

    print(f"\n📦 Chunk 流转:")
    print(f"   检索召回 → {results.get('chunk_count_before_dedup', '?')} 个")
    print(f"   去重后   → {results.get('chunk_count_after_dedup', '?')} 个")
    print(f"   Rerank后 → {results.get('chunk_count_after_rerank', '?')} 个")

    if results.get("retrieval_time_ms"):
        print(f"\n⏱️  检索耗时: {results['retrieval_time_ms']}ms")


# ============================================================
# 方式四（推荐）：改 Ragent 代码，让它自己打印 chunk 内容
# ============================================================

def print_code_modification_guide():
    """
    打印改代码的指导 —— 这是最彻底的方式
    在 Ragent 的 rerank 完成后，打印每个 chunk 的内容摘要
    """
    guide = """
╔══════════════════════════════════════════════════════════════════╗
║  🔧 推荐方案：改 Ragent 代码，让它自己打印 chunk 详情           ║
╚══════════════════════════════════════════════════════════════════╝

在 MultiChannelRetrievalEngine.java 的后置处理器链执行完成后，
加一段日志打印，让你能看到最终送给 LLM 的每个 chunk：

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

让 Claude Code 执行这个指令：

```
在 MultiChannelRetrievalEngine.java 中，找到 "后置处理器链执行完成" 
这行日志的下方，添加以下代码：

// ===== 检索透视：打印最终送给LLM的chunk详情 =====
for (int i = 0; i < finalChunks.size(); i++) {
    var chunk = finalChunks.get(i);
    String preview = chunk.getContent() != null && chunk.getContent().length() > 150 
        ? chunk.getContent().substring(0, 150) + "..." 
        : chunk.getContent();
    log.info("召回chunk[{}] 来源={} | 分数={} | 内容预览={}",
        i + 1,
        chunk.getDocName() != null ? chunk.getDocName() : "unknown",
        String.format("%.4f", chunk.getScore()),
        preview);
}
// ===== 检索透视结束 =====

注意：变量名可能不叫 finalChunks / getContent / getDocName / getScore，
请根据实际的 RetrievedChunk 类的字段名调整。
先帮我看一下 RetrievedChunk 类有哪些字段。
```

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

改完后重新打包部署，然后每次提问都能在日志里看到：

  召回chunk[1] 来源=第9小节：向量数据库.md | 分数=0.9234 | 内容预览=Milvus是一个...
  召回chunk[2] 来源=第10小节：向量检索.md | 分数=0.8876 | 内容预览=HNSW索引的核心...
  召回chunk[3] 来源=第6小节：数据分块.md | 分数=0.7123 | 内容预览=固定分块策略...
  ...

这样你就能直接看到：
  ✅ 分数排序是否合理
  ✅ 来源文档是否相关  
  ✅ chunk内容是否真的能回答问题
  ✅ 有没有"语义近但答案无关"的噪音chunk
"""
    print(guide)


# ============================================================
# 实验设计模板
# ============================================================

def print_experiment_templates():
    """打印可以直接跑的实验设计"""
    templates = """
╔══════════════════════════════════════════════════════════════════╗
║  🧪 实验设计模板 —— 从直觉到验证                                ║
╚══════════════════════════════════════════════════════════════════╝

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
实验1：低分chunk过滤阈值探索
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
直觉：score低于某个阈值的chunk大概率是噪音
假设：过滤score<0.65的chunk不会丢失关键信息，且能提升生成质量
实验：
  对照组：当前系统（不过滤）
  实验组：过滤score<0.65的chunk
  测试集：6个标准问题
  观察指标：
    - 每个问题过滤掉了几个chunk
    - 被过滤的chunk内容是什么（人工判断：噪音还是有用信息）
    - 最终回答质量有无变化
  判定标准：
    如果被过滤的chunk全是噪音 → 阈值合理，可以上线
    如果有有用信息被过滤 → 阈值偏高，降到0.5或0.6试试
    如果过滤后回答质量反而下降 → 说明"低分但有用"的情况存在

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
实验2：分块策略对召回质量的影响
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
直觉：structure_aware分块应该比固定分块召回更相关
假设：同一篇文档，structure_aware切分后的chunk在语义上更完整，
     召回时score更高且与问题更相关
实验：
  选一篇中等长度的文档（比如"查询重写与语义增强机制"）
  对照组：用固定分块(1400字)重新入库
  实验组：当前structure_aware分块
  测试：问3个关于这篇文档的问题
  观察指标：
    - 两种分块方式产生的chunk数量
    - 同一个问题，两种分块的召回chunk内容对比
    - 哪种分块的chunk"信息完整度"更高
  操作：用你的批量上传脚本，改chunkStrategy参数重新入库一份

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
实验3：rerank模型的"判断力"审计
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
直觉：rerank应该能把"语义近但答案无关"的chunk排低
假设：rerank前后，真正含有答案的chunk排名应该上升
实验：
  选一个你知道答案在哪篇文档的问题
  在代码中打印 rerank 前和 rerank 后的chunk排序对比
  观察指标：
    - rerank前：含答案的chunk排第几？
    - rerank后：含答案的chunk排第几？
    - rerank把什么chunk提上来了，把什么chunk压下去了
  关键改动：在 RerankPostProcessor 的 process 方法里加日志，
           打印 rerank 前后的 chunk 排名变化

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
实验4（创造性）：自适应TopK —— 让系统自己决定要多少chunk
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
直觉：简单问题不需要10个chunk，复杂问题可能需要更多
假设：根据score分布的"肘部"自动截断，比固定topK效果更好
      （类似聚类的elbow method）
实验：
  收集6个问题的完整score分布（20个chunk的分数列表）
  画出score衰减曲线，找到"陡降点"
  观察：
    - 简单问题（Q6前端技术栈）：前2-3个chunk分数高，后面急剧下降
    - 复杂问题（Q3跨文档）：前5-6个chunk分数都比较高，缓慢下降
  如果模式成立，就可以写一个自适应截断：
    score_drop = scores[i] - scores[i+1]
    if score_drop > threshold:  # 发现陡降
        cutoff = i + 1
  这是一个你自己发明的优化，不是照搬别人的方案

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""
    print(templates)


# ============================================================
# 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="Ragent 检索透视镜 —— 看清每次召回到底发生了什么",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("question", nargs="?", help="要检索的问题")
    parser.add_argument("--guide", action="store_true",
                       help="打印改代码的指导（推荐方案）")
    parser.add_argument("--experiments", action="store_true",
                       help="打印实验设计模板")
    parser.add_argument("--parse-log", metavar="FILE",
                       help="解析服务器日志文件中的检索信息")
    parser.add_argument("--milvus", action="store_true",
                       help="直接查询 Milvus（需要SSH隧道和pymilvus）")

    args = parser.parse_args()

    if args.guide:
        print_code_modification_guide()
    elif args.experiments:
        print_experiment_templates()
    elif args.parse_log:
        with open(args.parse_log, "r", encoding="utf-8") as f:
            log_text = f.read()
        results = parse_retrieval_log(log_text)
        print_retrieval_report(results)
    elif args.milvus:
        question = args.question or "测试问题"
        search_milvus_direct(question)
    elif args.question:
        chat_via_ragent(args.question)
    else:
        # 默认：打印使用指南
        print("""
Ragent 检索透视镜 —— 使用指南

最推荐的方式（改代码，一劳永逸）：
  python ragent_retrieval_inspector.py --guide

查看实验设计模板：
  python ragent_retrieval_inspector.py --experiments

解析服务器日志：
  python ragent_retrieval_inspector.py --parse-log app.log

直接查 Milvus（需要SSH隧道）：
  python ragent_retrieval_inspector.py --milvus "你的问题"
        """)


if __name__ == "__main__":
    main()
