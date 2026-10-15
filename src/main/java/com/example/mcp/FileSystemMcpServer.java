package com.example.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FileSystemMcpServer {

	// Define a safe sandbox directory for filesystem operations
	private static final Path ROOT_DIR = Paths.get(System.getProperty("user.home"), "mcp-sandbox");

	public static void main(String[] args) throws IOException {
		// Ensure the sandbox directory exists
		if (!Files.exists(ROOT_DIR)) {
			Files.createDirectories(ROOT_DIR);
		}

		// 1. Initialize the Stdio transport provider using SDK 2.x defaults
		StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

		// 2. Define Tool 1: read_file
        McpServerFeatures.SyncToolSpecification readFileTool = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("read_file")
                        .description("Reads the content of a text file from the sandbox directory")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of("type", "string", "description", "The name of the file to read")
                                ),
                                "required", List.of("filename")
                        ))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String filename = (String) request.arguments().get("filename");
                        Path filePath = ROOT_DIR.resolve(filename).normalize();
                        
                        // Security check against directory traversal outside the sandbox
                        if (!filePath.startsWith(ROOT_DIR)) {
                            throw new SecurityException("Access denied outside sandbox.");
                        }

                        String content = Files.readString(filePath);
                        
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(content)))
                                .isError(false)
                                .build();
                                
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                                .isError(true)
                                .build();
                    }
                })
                .build();

        // 3. Define Tool 2: list_directory
        McpServerFeatures.SyncToolSpecification listDirectoryTool = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("list_directory")
                        .description("Lists all files in the sandbox directory")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of()
                        ))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        List<String> files;
                        try (var stream = Files.list(ROOT_DIR)) {
                            files = stream
                                    .map(path -> path.getFileName().toString())
                                    .collect(Collectors.toList());
                        }
                        
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(files.toString())))
                                .isError(false)
                                .build();
                                
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                                .isError(true)
                                .build();
                    }
                })
                .build();

		// 4. Build and start the synchronous MCP Server
		McpSyncServer syncServer = McpServer.sync(transportProvider)
				.serverInfo("Java FileSystem MCP Server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(readFileTool, listDirectoryTool)
				.build();

		// Block and listen for incoming messages over standard input/output
		// (Note: Lifecycle management in SDK 2.x handles block-listening automatically)
	}
}
