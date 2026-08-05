package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.mcp.McpDispatcher
import com.brokenfinger.tracker.adapter.mcp.McpToolInvoker
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
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
    @Bean
    fun recordQuery(layout: RecordLayout): RecordQuery = RecordQuery(JsonlRecordStore(layout.submissionLog()))

    @Bean
    fun mcpToolInvoker(query: RecordQuery): McpToolInvoker = McpToolInvoker(query)

    @Bean
    fun mcpDispatcher(tools: McpToolInvoker): McpDispatcher = McpDispatcher(tools)
}
