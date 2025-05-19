package com.example;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

public class OllamaShellTool {
    private static final String OLLAMA_GENERATE_URL = "http://localhost:11434/api/generate";
    private static final String OLLAMA_CHAT_URL = "http://localhost:11434/api/chat";
    private static final String DEFAULT_MODEL = "llama2";
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w*)\\s*\\n([\\s\\S]*?)\\n```");
    
    private static String model = DEFAULT_MODEL;
    private static boolean verbose = false;
    private static Path workingDir;
    private static List<Map<String, String>> chatHistory = new ArrayList<>();
    private static String lastResponse = "";
    private static final Pattern FILE_PATTERN = Pattern.compile("\\[file: ([^\\]]+)\\]");
    
    public static void main(String[] args) {
        // Parse command line arguments
        parseArgs(args);
        
        // Set working directory
        workingDir = Paths.get(System.getProperty("user.dir"));
        
        Scanner scanner = new Scanner(System.in);
        System.out.println("🤖 Ollama Shell Tool (model: " + model + ")");
        System.out.println("Type '/help' for commands, '/exit' to quit");
        
        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();
            
            if (processCommand(input)) {
                continue;
            }
            
            // Check for file references and expand them
            input = expandFileReferences(input);
            
            // Add user message to history
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", input);
            chatHistory.add(userMessage);
            
            try {
                lastResponse = streamingChat(chatHistory);
                
                // Add assistant response to history
                Map<String, String> assistantMessage = new HashMap<>();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", lastResponse);
                chatHistory.add(assistantMessage);
            } catch (IOException e) {
                System.err.println("Error communicating with Ollama: " + e.getMessage());
            }
        }
    }
    
    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--model") && i + 1 < args.length) {
                model = args[i + 1];
                i++;
            } else if (args[i].equals("--verbose") || args[i].equals("-v")) {
                verbose = true;
            }
        }
    }
    
    private static boolean processCommand(String input) {
        if (input.equals("/exit") || input.equals("/quit")) {
            System.out.println("Goodbye!");
            System.exit(0);
            return true;
        } else if (input.equals("/help")) {
            printHelp();
            return true;
        } else if (input.equals("/clear")) {
            chatHistory.clear();
            System.out.println("Chat history cleared");
            return true;
        } else if (input.startsWith("/model ")) {
            model = input.substring(7).trim();
            System.out.println("Model changed to: " + model);
            return true;
        } else if (input.equals("/run")) {
            executeCodeFromResponse(lastResponse);
            return true;
        } else if (input.startsWith("/save ")) {
            String filename = input.substring(6).trim();
            saveCodeFromResponse(lastResponse, filename);
            return true;
        } else if (input.startsWith("/cd ")) {
            String path = input.substring(4).trim();
            changeDirectory(path);
            return true;
        } else if (input.equals("/pwd")) {
            System.out.println("Current directory: " + workingDir);
            return true;
        } else if (input.equals("/ls")) {
            listFiles();
            return true;
        }
        return false;
    }
    
    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /help       - Show this help");
        System.out.println("  /exit       - Exit the program");
        System.out.println("  /clear      - Clear chat history");
        System.out.println("  /model NAME - Change the Ollama model");
        System.out.println("  /run        - Extract and run the last code block");
        System.out.println("  /save FILE  - Save the last code block to FILE");
        System.out.println("  /cd PATH    - Change working directory");
        System.out.println("  /pwd        - Print working directory");
        System.out.println("  /ls         - List files in current directory");
        System.out.println("");
        System.out.println("Special syntax:");
        System.out.println("  [file: path/to/file.txt] - Include file content in your prompt");
    }
    
    private static void changeDirectory(String path) {
        try {
            Path newPath = workingDir.resolve(path).normalize();
            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                workingDir = newPath;
                System.out.println("Changed to directory: " + workingDir);
            } else {
                System.out.println("Directory does not exist: " + newPath);
            }
        } catch (Exception e) {
            System.err.println("Error changing directory: " + e.getMessage());
        }
    }
    
    private static void listFiles() {
        try {
            Files.list(workingDir).forEach(path -> {
                String type = Files.isDirectory(path) ? "[DIR] " : "[FILE] ";
                System.out.println(type + path.getFileName());
            });
        } catch (IOException e) {
            System.err.println("Error listing files: " + e.getMessage());
        }
    }
    
    private static String expandFileReferences(String input) {
        Matcher matcher = FILE_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String filename = matcher.group(1);
            try {
                Path filePath = workingDir.resolve(filename);
                String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                String fileExtension = getFileExtension(filename);
                
                // Format file content as a code block
                String replacement = "```" + fileExtension + "\n" + content + "\n```\n";
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
                
                if (verbose) {
                    System.out.println("Included file: " + filePath);
                }
            } catch (IOException e) {
                System.err.println("Error reading file " + filename + ": " + e.getMessage());
                matcher.appendReplacement(result, Matcher.quoteReplacement("[Error reading file: " + filename + "]"));
            }
        }
        
        matcher.appendTail(result);
        return result.toString();
    }
    
    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }
    
    private static void executeCodeFromResponse(String response) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            String language = matcher.group(1).toLowerCase();
            String code = matcher.group(2);
            
            if (language.equals("java")) {
                executeJavaCode(code);
            } else if (language.equals("python") || language.equals("py")) {
                executePythonCode(code);
            } else if (language.equals("javascript") || language.equals("js")) {
                executeJavaScriptCode(code);
            } else if (language.equals("bash") || language.equals("sh")) {
                executeBashCode(code);
            } else {
                System.out.println("Unsupported language for execution: " + language);
            }
        } else {
            System.out.println("No code block found in the last response");
        }
    }
    
    private static void saveCodeFromResponse(String response, String filename) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            String code = matcher.group(2);
            try {
                Path path = workingDir.resolve(filename);
                Files.write(path, code.getBytes(StandardCharsets.UTF_8));
                System.out.println("Code saved to " + path);
            } catch (IOException e) {
                System.err.println("Error saving file: " + e.getMessage());
            }
        } else {
            System.out.println("No code block found in the last response");
        }
    }
    
    private static void executeJavaCode(String code) {
        try {
            // Save to a temporary file
            Path javaFile = Files.createTempFile(workingDir, "JavaTemp", ".java");
            Files.write(javaFile, code.getBytes(StandardCharsets.UTF_8));
            
            // Extract class name
            Pattern classPattern = Pattern.compile("public\\s+class\\s+(\\w+)");
            Matcher classMatcher = classPattern.matcher(code);
            
            if (!classMatcher.find()) {
                System.out.println("Could not find public class name in code");
                return;
            }
            
            String className = classMatcher.group(1);
            
            // Compile
            Process compileProcess = new ProcessBuilder("javac", javaFile.toString())
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader compileReader = new BufferedReader(
                    new InputStreamReader(compileProcess.getInputStream()));
            
            String line;
            while ((line = compileReader.readLine()) != null) {
                System.out.println(line);
            }
            
            int compileResult = compileProcess.waitFor();
            if (compileResult != 0) {
                System.out.println("Compilation failed");
                return;
            }
            
            // Run
            Process runProcess = new ProcessBuilder("java", "-cp", workingDir.toString(), className)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader runReader = new BufferedReader(
                    new InputStreamReader(runProcess.getInputStream()));
            
            while ((line = runReader.readLine()) != null) {
                System.out.println(line);
            }
            
            runProcess.waitFor();
            
        } catch (IOException | InterruptedException e) {
            System.err.println("Error executing Java code: " + e.getMessage());
        }
    }
    
    private static void executePythonCode(String code) {
        try {
            // Save to a temporary file
            Path pythonFile = Files.createTempFile(workingDir, "PythonTemp", ".py");
            Files.write(pythonFile, code.getBytes(StandardCharsets.UTF_8));
            
            // Run
            Process process = new ProcessBuilder("python", pythonFile.toString())
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            System.err.println("Error executing Python code: " + e.getMessage());
        }
    }
    
    private static void executeJavaScriptCode(String code) {
        try {
            // Save to a temporary file
            Path jsFile = Files.createTempFile(workingDir, "JsTemp", ".js");
            Files.write(jsFile, code.getBytes(StandardCharsets.UTF_8));
            
            // Run
            Process process = new ProcessBuilder("node", jsFile.toString())
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            System.err.println("Error executing JavaScript code: " + e.getMessage());
        }
    }
    
    private static void executeBashCode(String code) {
        try {
            // Save to a temporary file
            Path bashFile = Files.createTempFile(workingDir, "BashTemp", ".sh");
            Files.write(bashFile, code.getBytes(StandardCharsets.UTF_8));
            
            // Make executable
            bashFile.toFile().setExecutable(true);
            
            // Run
            Process process = new ProcessBuilder("/bin/bash", bashFile.toString())
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            System.err.println("Error executing Bash code: " + e.getMessage());
        }
    }
    
    private static String streamingChat(List<Map<String, String>> messages) throws IOException {
        URL url = new URL(OLLAMA_CHAT_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        
        // Create request JSON
        JSONObject requestJson = new JSONObject();
        requestJson.put("model", model);
        requestJson.put("messages", messages);
        requestJson.put("stream", true);
        
        // Send request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestJson.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        // Read streaming response
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        
        StringBuilder fullResponse = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            
            JSONObject jsonResponse = new JSONObject(line);
            
            if (jsonResponse.has("message")) {
                JSONObject message = jsonResponse.getJSONObject("message");
                if (message.has("content")) {
                    String content = message.getString("content");
                    System.out.print(content);
                    fullResponse.append(content);
                }
            }
            
            if (jsonResponse.has("done") && jsonResponse.getBoolean("done")) {
                break;
            }
        }
        
        System.out.println();
        return fullResponse.toString();
    }
}
