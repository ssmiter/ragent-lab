#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Microcompact Demo - 展示压缩前后对比案例

读取 transcript 文件，模拟 MicrocompactProcessor 的处理逻辑，
展示几个具体的压缩前后对比案例，帮助"祛魅"。
"""

import json
import re
from pathlib import Path

# 正则模式（适配实际返回格式）
COLLECTION_PATTERN = re.compile(r'在知识库 \[([\w]+)\]')
CHUNK_COUNT_PATTERN = re.compile(r'找到 (\d+) 个片段')
RERANK_SCORE_PATTERN = re.compile(r'Rerank分数: ([\d.]+)')
SOURCE_PATTERN = re.compile(r'\(来源: ([\w.-]+)\)')
HIGHEST_SCORE_PATTERN = re.compile(r'最高分=([\d.]+)')

# 也兼容 Java 代码中的模式
COLLECTION_PATTERN_ALT = re.compile(r'searched collection: ([\w]+)')
CHUNK_COUNT_PATTERN_ALT = re.compile(r'Found (\d+) relevant chunks')


def generate_summary(original_content: str, turn: int, age_threshold: int = 3) -> str:
    """模拟 MicrocompactProcessor 的 generateSummary 方法"""
    summary_parts = [f"[已压缩的搜索结果 - Turn {turn}]"]

    # 尝试解析实际返回格式："在知识库 [ragentdocs] 中找到 5 个片段"
    collection_match = COLLECTION_PATTERN.search(original_content)
    if not collection_match:
        collection_match = COLLECTION_PATTERN_ALT.search(original_content)

    chunk_count_match = CHUNK_COUNT_PATTERN.search(original_content)
    if not chunk_count_match:
        chunk_count_match = CHUNK_COUNT_PATTERN_ALT.search(original_content)

    if collection_match or chunk_count_match:
        # 提取知识库名
        if collection_match:
            summary_parts.append(f"知识库: {collection_match.group(1)}")

        # 提取片段数量
        if chunk_count_match:
            summary_parts.append(f"返回: {chunk_count_match.group(1)} 个片段")

        # 提取最高分（遍历所有分数找最大值）
        scores = RERANK_SCORE_PATTERN.findall(original_content)
        if scores:
            highest_score = max(float(s) for s in scores)
            summary_parts[-1] += f", 最高分 {highest_score:.2f}"

        # 提取主要来源（取前3个）
        sources = []
        for source_match in SOURCE_PATTERN.finditer(original_content):
            sources.append(source_match.group(1))
            if len(sources) >= 3:
                break

        if sources:
            # 过滤掉 "未知来源"
            sources = [s for s in sources if s != '未知来源']
            if sources:
                source_str = ", ".join(sources)
                # 计算总来源数
                total_sources = len([s for s in SOURCE_PATTERN.findall(original_content) if s != '未知来源'])
                if len(sources) < total_sources:
                    source_str += " 等"
                summary_parts[-1] += f", 来源: {source_str}"

    elif 'rewrite_query' in original_content or '改写' in original_content:
        summary_parts.append("工具: rewrite_query")
        if len(original_content) > 100:
            summary_parts.append(f"结果: {original_content[:100]}...")
        else:
            summary_parts.append(f"结果: {original_content}")

    else:
        summary_parts.append("类型: 其他内容")
        if len(original_content) > 50:
            summary_parts.append(f"摘要: {original_content[:50]}...")
        else:
            summary_parts.append(f"内容: {original_content}")

    summary_parts.append("完整记录已保存至 transcript 文件")

    return "\n".join(summary_parts)


def load_transcript(filepath: str):
    """加载 transcript 文件（多行 JSON 格式）"""
    records = []
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # TranscriptWriter 使用 pretty-printing，导致每个 JSON 是多行的
    # 需要手动解析多个 JSON 对象
    import json.decoder

    decoder = json.JSONDecoder()
    idx = 0
    while idx < len(content):
        # 跳过空白字符
        while idx < len(content) and content[idx] in ' \n\r\t':
            idx += 1
        if idx >= len(content):
            break

        try:
            obj, end_idx = decoder.raw_decode(content, idx)
            records.append(obj)
            idx = end_idx
        except json.JSONDecodeError as e:
            print(f"解析错误 at idx={idx}: {e}")
            # 跳过到下一个可能的位置
            idx += 1

    return records


def build_tool_result_turn_map(records):
    """构建 tool_call_id 到 turn 的映射"""
    tool_result_turn_map = {}
    current_turn = 0

    for record in records:
        role = record.get('role', '')

        if role == 'user':
            current_turn += 1
        elif role == 'assistant':
            tool_calls = record.get('tool_calls', [])
            for tc in tool_calls:
                tc_id = tc.get('id', '')
                if tc_id:
                    tool_result_turn_map[tc_id] = current_turn
        elif role == 'tool':
            # 记录 tool_result 的原始内容
            pass

    return tool_result_turn_map


def find_compact_candidates(records, age_threshold=3):
    """找出应该被压缩的 tool_result"""
    tool_result_turn_map = build_tool_result_turn_map(records)
    candidates = []

    # 计算最大 turn
    max_turn = 0
    for record in records:
        if record.get('role') == 'user':
            max_turn = record.get('turn', max_turn)

    # 当前假设是最后一轮
    current_turn = max_turn

    for record in records:
        if record.get('role') == 'tool':
            tool_call_id = record.get('tool_call_id', '')
            turn_when_created = tool_result_turn_map.get(tool_call_id)

            if turn_when_created:
                age = current_turn - turn_when_created
                if age > age_threshold:
                    candidates.append({
                        'record': record,
                        'turn': turn_when_created,
                        'age': age,
                        'tool_call_id': tool_call_id
                    })

    return candidates


def main():
    """主函数"""
    # Transcript 文件路径
    transcript_path = Path("experiment/results/experiment4/transcripts/run1_baseline_20260407_190956.jsonl")
    output_path = Path("experiment/results/experiment4/compact_examples_report.md")

    if not transcript_path.exists():
        print(f"文件不存在: {transcript_path}")
        return

    print(f"加载 transcript: {transcript_path}")
    records = load_transcript(str(transcript_path))
    print(f"总记录数: {len(records)}")

    # 找出应被压缩的 tool_result（假设当前在 Turn 15，阈值 3）
    print("模拟 Microcompact: 当前 Turn=15, 阈值=3")
    candidates = find_compact_candidates(records, age_threshold=3)
    print(f"找到 {len(candidates)} 条应压缩的 tool_result")

    # 按原始内容长度排序，展示最大的几个
    candidates_sorted = sorted(candidates, key=lambda x: len(x['record'].get('content', '')), reverse=True)

    # 生成报告内容
    report_lines = []
    report_lines.append("# Microcompact 压缩前后对比案例")
    report_lines.append("")
    report_lines.append(f"模拟条件: 当前 Turn=15, 年龄阈值=3")
    report_lines.append(f"总压缩数: {len(candidates)} 条 tool_result")
    report_lines.append("")
    report_lines.append("---")
    report_lines.append("")

    for i, candidate in enumerate(candidates_sorted[:5]):
        record = candidate['record']
        original_content = record.get('content', '')
        turn = candidate['turn']
        age = candidate['age']
        tool_call_id = candidate['tool_call_id']

        summary = generate_summary(original_content, turn)

        report_lines.append(f"## 案例 {i+1}: ToolCallId={tool_call_id}")
        report_lines.append("")
        report_lines.append(f"- **原始轮次**: Turn {turn}")
        report_lines.append(f"- **年龄**: {age} 轮")
        report_lines.append(f"- **原始字符数**: {len(original_content)}")
        report_lines.append(f"- **压缩后字符数**: {len(summary)}")
        report_lines.append(f"- **节省**: {len(original_content) - len(summary)} 字符 ({(len(original_content) - len(summary)) * 100 / len(original_content):.1f}%)")
        report_lines.append("")

        report_lines.append("### 原始内容 (前500字符)")
        report_lines.append("")
        report_lines.append("```")
        truncated_original = original_content[:500] + "..." if len(original_content) > 500 else original_content
        report_lines.append(truncated_original)
        report_lines.append("```")
        report_lines.append("")

        report_lines.append("### 压缩后内容")
        report_lines.append("")
        report_lines.append("```")
        report_lines.append(summary)
        report_lines.append("```")
        report_lines.append("")
        report_lines.append("---")
        report_lines.append("")

    # 统计信息
    total_original_chars = sum(len(c['record'].get('content', '')) for c in candidates)
    total_summary_chars = sum(len(generate_summary(c['record'].get('content', ''), c['turn'])) for c in candidates)

    report_lines.append("## 统计摘要")
    report_lines.append("")
    report_lines.append(f"- **总压缩数**: {len(candidates)} 条")
    report_lines.append(f"- **原始总字符**: {total_original_chars}")
    report_lines.append(f"- **压缩后总字符**: {total_summary_chars}")
    report_lines.append(f"- **总节省**: {total_original_chars - total_summary_chars} 字符 ({(total_original_chars - total_summary_chars) * 100 / total_original_chars:.1f}%)")
    report_lines.append("")
    report_lines.append("---")
    report_lines.append("")
    report_lines.append("*报告生成于 2026-04-07*")

    # 写入文件
    report_content = "\n".join(report_lines)
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(report_content)

    print(f"报告已生成: {output_path}")


if __name__ == '__main__':
    main()