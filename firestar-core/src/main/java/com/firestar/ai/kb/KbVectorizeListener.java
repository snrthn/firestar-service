package com.firestar.ai.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.ai.config.EmbeddingProperties;
import com.firestar.config.ExecutorConfig;
import com.firestar.ai.embedding.EmbeddingProvider;
import com.firestar.ai.entity.KbChunk;
import com.firestar.ai.entity.KbDoc;
import com.firestar.ai.es.EsVectorStore;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 知识库向量化管道（P1#5-b）。
 *
 * <p>职责：① 文档发布事务提交后（AFTER_COMMIT），异步把新版本切片向量化写入 ES，
 * 并回填 {@code vector_status=READY}/{@code embedding_ref}；② 文档删除后清 ES 向量防孤儿；
 * ③ 提供 {@link #backfill()} 供运维回填历史 NONE/PENDING 切片。</p>
 *
 * <p><b>装配条件</b>：既有 EmbeddingProvider（provider≠none）又有 EsVectorStore（retriever=hybrid）
 * 时才装配——缺一不可，避免半套配置下空跑。向量化失败只置 FAILED 并记日志，绝不抛异常
 * 影响发布/删除主流程（异步旁路，失败可回填重试）。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever", havingValue = "hybrid")
public class KbVectorizeListener {

    private static final Logger log = LoggerFactory.getLogger(KbVectorizeListener.class);

    private static final String VS_PENDING = "PENDING";
    private static final String VS_READY = "READY";
    private static final String VS_FAILED = "FAILED";

    private final KbChunkMapper chunkMapper;
    private final KbDocMapper docMapper;
    private final EmbeddingProvider embeddingProvider;
    private final EsVectorStore vectorStore;
    private final Executor executor;
    private final EmbeddingProperties props;
    private final KbVectorEgressPolicy vectorEgressPolicy;

    @Autowired
    public KbVectorizeListener(KbChunkMapper chunkMapper,
                               KbDocMapper docMapper,
                               EmbeddingProvider embeddingProvider,
                               EsVectorStore vectorStore,
                               @Qualifier(ExecutorConfig.KB_VECTORIZE_EXECUTOR) Executor executor,
                               EmbeddingProperties props,
                               KbVectorEgressPolicy vectorEgressPolicy) {
        this.chunkMapper = chunkMapper;
        this.docMapper = docMapper;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.executor = executor;
        this.props = props;
        this.vectorEgressPolicy = vectorEgressPolicy;
    }

    /** 发布事务提交后：异步向量化新版本切片。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublished(KbPublishedEvent event) {
        executor.execute(() -> {
            try {
                int n = vectorizeDoc(event.docId(), event.version());
                log.info("[KB-VECTOR] 发布向量化完成 docId={} v={} chunks={} provider={}",
                        event.docId(), event.version(), n, embeddingProvider.id());
            } catch (Exception e) {
                log.error("[KB-VECTOR] 发布向量化失败 docId={} v={}: {}",
                        event.docId(), event.version(), e.getMessage(), e);
            }
        });
    }

    /** 删除事务提交后：异步清理 ES 向量。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(KbDocDeletedEvent event) {
        executor.execute(() -> {
            try {
                vectorStore.deleteByDocId(event.docId());
                log.info("[KB-VECTOR] 删除向量同步完成 docId={}", event.docId());
            } catch (Exception e) {
                log.error("[KB-VECTOR] 删除向量同步失败 docId={}: {}", event.docId(), e.getMessage(), e);
            }
        });
    }

    /** 向量化指定文档当前版本切片，返回成功条数。发布新版本前由本管道先清该 doc 旧向量。 */
    public int vectorizeDoc(long docId, int version) throws Exception {
        List<KbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocId, docId)
                .eq(KbChunk::getVersion, version)
                .eq(KbChunk::getStatus, 1));
        if (chunks.isEmpty()) {
            return 0;
        }
        // 先清旧向量：重发布/回填时避免同 doc 旧版本残留
        vectorStore.deleteByDocId(docId);

        // 出域闸：禁出域/敏感角色文档正文不送第三方 embedding。
        // 清旧向量之后拦截（防已向量化的敏感文档残留），标 PENDING 之前（不动切片状态，保持 NONE）。
        KbDoc doc = docMapper.selectById(docId);
        if (doc == null || !vectorEgressPolicy.canEmbed(doc)) {
            log.info("[KB-VECTOR] 出域拦截，跳过向量化 docId={} v={} policy={} chunks={}",
                    docId, version, vectorEgressPolicy.id(), chunks.size());
            return 0;
        }

        // 先标 PENDING
        for (KbChunk c : chunks) {
            chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                    .eq(KbChunk::getId, c.getId())
                    .set(KbChunk::getVectorStatus, VS_PENDING));
        }
        // 批量向量化（单条失败不影响整批：逐条 embed，失败置 FAILED）
        int ok = 0;
        List<String> texts = new ArrayList<>(chunks.size());
        for (KbChunk c : chunks) {
            String text = (c.getHeadingPath() == null ? "" : c.getHeadingPath() + "\n")
                    + (c.getContent() == null ? "" : c.getContent());
            texts.add(text);
        }
        List<float[]> vectors;
        try {
            vectors = embeddingProvider.embed(texts);
        } catch (Exception e) {
            log.error("[KB-VECTOR] 批量向量化失败 docId={} v={}: {}", docId, version, e.getMessage());
            for (KbChunk c : chunks) {
                markFailed(c.getId());
            }
            throw e;
        }
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk c = chunks.get(i);
            try {
                float[] vec = vectors.get(i);
                vectorStore.upsert(c.getId(), c.getDocId(), c.getVersion(),
                        c.getHeadingPath(), c.getContent(), c.getSourceRef(), vec);
                chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                        .eq(KbChunk::getId, c.getId())
                        .set(KbChunk::getVectorStatus, VS_READY)
                        .set(KbChunk::getEmbeddingRef, vectorStore.index() + "/" + c.getId()));
                ok++;
            } catch (Exception e) {
                log.warn("[KB-VECTOR] 单条向量化失败 chunkId={}: {}", c.getId(), e.getMessage());
                markFailed(c.getId());
            }
        }
        return ok;
    }

    /**
     * 历史回填：扫描所有 vector_status IN (NONE/PENDING/FAILED) 的有效切片，按 doc+version 分组
     * 逐一向量化。运维入口（管理端脚本/临时接口调用），单次全量，切片量大时分批调用方自行限流。
     *
     * @return 成功向量化的切片总数
     */
    public int backfill() {
        List<KbChunk> pending = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getStatus, 1)
                .in(KbChunk::getVectorStatus, "NONE", VS_PENDING, VS_FAILED));
        log.info("[KB-VECTOR] 回填扫描待处理切片 {} 条（dims={}, provider={}）",
                pending.size(), props.getDims(), embeddingProvider.id());
        var groups = new java.util.LinkedHashMap<Long, java.util.Set<Integer>>();
        for (KbChunk c : pending) {
            groups.computeIfAbsent(c.getDocId(), k -> new java.util.LinkedHashSet<>()).add(c.getVersion());
        }
        int total = 0;
        for (var e : groups.entrySet()) {
            for (int v : e.getValue()) {
                try {
                    total += vectorizeDoc(e.getKey(), v);
                } catch (Exception ex) {
                    log.warn("[KB-VECTOR] 回填 docId={} v={} 失败: {}", e.getKey(), v, ex.getMessage());
                }
            }
        }
        log.info("[KB-VECTOR] 回填完成，成功 {} 条", total);
        return total;
    }

    private void markFailed(Long chunkId) {
        chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                .eq(KbChunk::getId, chunkId)
                .set(KbChunk::getVectorStatus, VS_FAILED));
    }
}
