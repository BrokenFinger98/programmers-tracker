package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.mcp.McpDispatcher
import com.brokenfinger.tracker.adapter.mcp.McpToolInvoker
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.application.ProblemCatalog
import com.brokenfinger.tracker.application.RecordQuery
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Assembles the read side — the half of design §7 that hands records to an AI.
 *
 * It shares the layout with the capture path but gets its own store instance, and that
 * store is only ever read from. The write path keeps its own, so nothing here can grow a
 * way to append: the read side has no `RecordWriter` and no `GitSync` in reach at all.
 */
@Configuration
class McpConfiguration {
    // The catalog bean is shared with the capture path rather than loaded twice: it is an
    // immutable snapshot read from the classpath, so a second copy would only cost memory.
    @Bean
    fun recordQuery(layout: RecordLayout, catalog: ProblemCatalog): RecordQuery =
        RecordQuery(JsonlRecordStore(layout.submissionLog()), catalog)

    @Bean
    fun mcpToolInvoker(query: RecordQuery): McpToolInvoker = McpToolInvoker(query)

    @Bean
    fun mcpDispatcher(tools: McpToolInvoker): McpDispatcher = McpDispatcher(tools)
}
