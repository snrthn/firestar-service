package com.firestar.ai.kb;

import com.firestar.ai.entity.KbChunk;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库 Markdown 切片器（纯函数，无 Spring / 无 Mapper / 无 HTTP，可直接单测）。
 *
 * <p>对齐设计 §3.3 / §3.4（LangChain {@code MarkdownHeaderTextSplitter} + 限长）：
 * <ul>
 *   <li><b>heading-aware</b>：按 H1/H2/H3 分节，每个 chunk 带 {@code headingPath}（如「公司报销制度 > 住宿标准」）；</li>
 *   <li>单文档唯一 H1（由 {@code KbTreeRules.validateMarkdown} 在保存/发布时保证，本类不再校验）；</li>
 *   <li>上限 {@value #CHUNK_TOKEN_CAP} token，跨上限时在<b>同一标题块内</b>硬切，overlap {@value #CHUNK_OVERLAP} 不跨标题边界；</li>
 *   <li><b>表格整块保留</b>：连续 {@code |} 行视为一个原子单元，不被按行拆散；fenced code 同理；</li>
 *   <li>每个 chunk 带 {@code anchor}（标题 slug，出处跳转用）与 {@code sourceRef}
 *       （{@code kb:doc/<docId>/v<version>/<anchor>}）；</li>
 *   <li>直接产出 {@link KbChunk} 实体（含 docId / version / status=1 / vectorStatus=NONE），publish 时批量落库。</li>
 * </ul>
 * fenced code / heading 判定口径与 {@code KbTreeRules} 完全一致（同 {@code FENCE}/{@code HEADING} 正则）。</p>
 */
public final class KbMarkdownSplitter {

    /** 单 chunk token 软上限（设计 §3.4） */
    public static final int CHUNK_TOKEN_CAP = 800;
    /** 跨块重叠 token（不跨标题边界，设计 §3.3） */
    public static final int CHUNK_OVERLAP = 150;

    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|?\\s*$");

    private KbMarkdownSplitter() {
    }

    /**
     * 把一篇 MD 文档切成 chunk 实体。
     *
     * @param docId   文档 id（HelpArticle 用负 id 虚拟）
     * @param version 文档版本（HelpArticle 固定 1）
     * @param md      已通过 {@code validateMarkdown} 的 Markdown 正文
     * @return 有序 chunk 列表，seq 从 0 起；空正文返回空列表
     */
    public static List<KbChunk> splitToChunks(long docId, int version, String md) {
        List<Block> blocks = parseBlocks(md);
        List<KbChunk> chunks = new ArrayList<>();
        int seq = 0;
        for (Block b : blocks) {
            for (ChunkText ct : splitBlock(b)) {
                KbChunk c = new KbChunk();
                c.setDocId(docId);
                c.setVersion(version);
                c.setSeq(seq++);
                c.setHeadingPath(b.path);
                c.setAnchor(b.anchor);
                c.setContent(ct.content);
                c.setTokenCnt(ct.tokens);
                c.setSourceRef("kb:doc/" + docId + "/v" + version + "/" + b.anchor);
                c.setEmbeddingRef(null);
                c.setVectorStatus("NONE");
                c.setStatus(1);
                c.setCreatedAt(LocalDateTime.now());
                c.setTenantId(0L);
                chunks.add(c);
            }
        }
        return chunks;
    }

    // ==================== 解析：MD → heading 块 ====================

    /** heading 块：标题层级 + 标题文本 + 标题路径（祖先标题） + anchor + body 行 */
    private static final class Block {
        final int level;
        final String title;
        final String path;
        final String anchor;
        final List<String> body;

        Block(int level, String title, String path, String anchor, List<String> body) {
            this.level = level;
            this.title = title;
            this.path = path;
            this.anchor = anchor;
            this.body = body;
        }
    }

    private static List<Block> parseBlocks(String md) {
        List<Block> blocks = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return blocks;
        }
        String[] lines = md.split("\n", -1);
        // heading 栈：维护当前路径（用 List 保证拼接顺序 = 祖先→自身）
        List<Integer> levelStack = new ArrayList<>();
        List<String> titleStack = new ArrayList<>();
        List<String> curBody = null;
        int curLevel = 0;
        String curTitle = "";
        String curPath = "";
        String curAnchor = "";
        boolean inFence = false;
        char fenceChar = 0;
        int blockSeq = 0;

        for (String line : lines) {
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                char c = fence.group(1).charAt(0);
                if (!inFence) {
                    inFence = true;
                    fenceChar = c;
                } else if (c == fenceChar) {
                    inFence = false;
                    fenceChar = 0;
                }
            }
            Matcher h = inFence ? null : HEADING.matcher(line);
            if (h != null && h.find()) {
                // 收尾上一个块
                if (curBody != null) {
                    flushBlock(blocks, curLevel, curTitle, curPath, curAnchor, curBody, blockSeq++);
                }
                int level = h.group(1).length();
                String title = h.group(2).trim();
                // 维护路径栈：弹出同级或更深的祖先，保证 path = 祖先 > 自身
                while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) >= level) {
                    levelStack.remove(levelStack.size() - 1);
                    titleStack.remove(titleStack.size() - 1);
                }
                levelStack.add(level);
                titleStack.add(title);
                curLevel = level;
                curTitle = title;
                curAnchor = slug(title, blockSeq);
                curPath = String.join(" > ", titleStack);
                curBody = new ArrayList<>();
                continue;
            }
            if (curBody != null) {
                curBody.add(line);
            }
        }
        if (curBody != null) {
            flushBlock(blocks, curLevel, curTitle, curPath, curAnchor, curBody, blockSeq);
        }
        return blocks;
    }

    private static void flushBlock(List<Block> blocks, int level, String title, String path,
                                   String anchor, List<String> body, int blockSeq) {
        // 去掉 body 首尾空行（标题与下一块之间的纯空白不占 chunk）
        List<String> trimmed = trimBlankEdges(body);
        if (trimmed.isEmpty() && level == 1) {
            // 仅有 H1 无正文：保留一个空 body 块以生成「封面」chunk（便于无正文文档也能被召回）
            blocks.add(new Block(level, title, path, anchor, new ArrayList<>()));
            return;
        }
        if (trimmed.isEmpty()) {
            return; // 空 H2/H3 不产生 chunk
        }
        blocks.add(new Block(level, title, path, anchor, trimmed));
    }

    private static List<String> trimBlankEdges(List<String> body) {
        int s = 0, e = body.size();
        while (s < e && body.get(s).isBlank()) {
            s++;
        }
        while (e > s && body.get(e - 1).isBlank()) {
            e--;
        }
        return new ArrayList<>(body.subList(s, e));
    }

    // ==================== 切分：heading 块 → 不超过上限的 chunk ====================

    /** 一个 chunk 的文本与 token 数 */
    private static final class ChunkText {
        final String content;
        final int tokens;

        ChunkText(String content, int tokens) {
            this.content = content;
            this.tokens = tokens;
        }
    }

    private static List<ChunkText> splitBlock(Block b) {
        String headingLine = "#".repeat(b.level) + " " + b.title;
        int headingTokens = KbTokenCounter.estimate(headingLine);
        int available = Math.max(CHUNK_TOKEN_CAP - headingTokens, 1);

        List<String> units = atomicUnits(b.body);
        // 超长单元（如整段无换行的长段落）先按 token 预算拆成小段，避免单 chunk 超限
        List<String> expanded = new ArrayList<>();
        for (String u : units) {
            if (KbTokenCounter.estimate(u) > available) {
                expanded.addAll(splitLongText(u, available));
            } else {
                expanded.add(u);
            }
        }
        List<ChunkText> out = new ArrayList<>();
        if (expanded.isEmpty()) {
            // 无 body：仅标题一个 chunk
            out.add(new ChunkText(headingLine, headingTokens));
            return out;
        }

        List<String> cur = new ArrayList<>();
        int curTokens = 0;
        List<String> prevTail = new ArrayList<>();
        for (String unit : expanded) {
            int uTok = KbTokenCounter.estimate(unit);
            if (curTokens > 0 && curTokens + uTok > available) {
                emitChunk(out, headingLine, prevTail, cur);
                prevTail = tailForOverlap(cur, CHUNK_OVERLAP);
                cur = new ArrayList<>();
                curTokens = 0;
            }
            cur.add(unit);
            curTokens += uTok;
        }
        if (!cur.isEmpty()) {
            emitChunk(out, headingLine, prevTail, cur);
        }
        return out;
    }

    /** 把若干 body 行聚成原子单元：连续表格行 / fenced code 各自成块，其余逐行 */
    private static List<String> atomicUnits(List<String> body) {
        List<String> units = new ArrayList<>();
        StringBuilder table = null;
        boolean fenceOpen = false;
        char fenceChar = 0;
        StringBuilder fenceBuf = null;
        for (String line : body) {
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                char c = fence.group(1).charAt(0);
                if (!fenceOpen) {
                    // 关闭之前可能打开的表格
                    if (table != null) {
                        units.add(table.toString());
                        table = null;
                    }
                    fenceOpen = true;
                    fenceChar = c;
                    fenceBuf = new StringBuilder();
                    fenceBuf.append(line).append('\n');
                    continue;
                } else if (c == fenceChar) {
                    fenceOpen = false;
                    fenceBuf.append(line).append('\n');
                    units.add(fenceBuf.toString());
                    fenceBuf = null;
                    continue;
                }
            }
            if (fenceOpen) {
                fenceBuf.append(line).append('\n');
                continue;
            }
            if (TABLE_ROW.matcher(line).find()) {
                if (table == null) {
                    table = new StringBuilder();
                }
                table.append(line).append('\n');
            } else {
                if (table != null) {
                    units.add(table.toString());
                    table = null;
                }
                units.add(line);
            }
        }
        if (table != null) {
            units.add(table.toString());
        }
        if (fenceBuf != null) {
            units.add(fenceBuf.toString());
        }
        return units;
    }

    private static void emitChunk(List<ChunkText> out, String headingLine,
                                  List<String> prevTail, List<String> cur) {
        StringBuilder sb = new StringBuilder();
        sb.append(headingLine);
        for (String u : prevTail) {
            sb.append('\n').append(u);
        }
        for (String u : cur) {
            sb.append('\n').append(u);
        }
        String content = sb.toString();
        out.add(new ChunkText(content, KbTokenCounter.estimate(content)));
    }

    /** 取 cur 尾部若干单元，token 合计不超过 overlap（不跨标题边界） */
    private static List<String> tailForOverlap(List<String> cur, int overlap) {
        List<String> tail = new ArrayList<>();
        int tok = 0;
        for (int i = cur.size() - 1; i >= 0 && tok < overlap; i--) {
            int uTok = KbTokenCounter.estimate(cur.get(i));
            tail.add(0, cur.get(i));
            tok += uTok;
        }
        return tail;
    }

    /**
     * 把超长单元（整段无换行的长段落 / 长代码块）按 token 预算切成小段。
     * 逐码点累计估算，达到预算即切；不跨标题边界（调用方保证传入的是同一 heading 块内的内容）。
     */
    private static List<String> splitLongText(String text, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int cjk = 0;
        int rest = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (!Character.isWhitespace(cp)) {
                if (isCjkChar(cp)) {
                    cjk++;
                } else {
                    rest++;
                }
            }
            cur.appendCodePoint(cp);
            if (cjk + ceilDiv(rest, 4) >= maxTokens && cur.length() > 0) {
                pieces.add(cur.toString());
                cur.setLength(0);
                cjk = 0;
                rest = 0;
            }
            i += len;
        }
        if (cur.length() > 0) {
            pieces.add(cur.toString());
        }
        return pieces;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static boolean isCjkChar(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /** anchor：标题 lower + 非字母数字转 - + 同级序号兜底，保证单文档内可读且唯一 */
    private static String slug(String title, int fallbackSeq) {
        if (title == null || title.isBlank()) {
            return "sec-" + fallbackSeq;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            int cp = title.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                sb.append(Character.toLowerCase((char) cp));
            } else if (Character.isWhitespace(cp) || cp == '-' || cp == '_') {
                sb.append('-');
            }
            // 其他标点直接丢弃
        }
        String s = sb.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? ("sec-" + fallbackSeq) : s;
    }
}
