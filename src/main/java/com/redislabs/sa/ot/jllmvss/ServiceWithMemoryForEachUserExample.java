package com.redislabs.sa.ot.jllmvss;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

import java.util.Scanner;

/**
 * Note this example borrowed almost exactly from:
 * https://github.com/langchain4j/langchain4j-examples/blob/main/other-examples/src/main/java/ChatMemoryExamples.java
 */
public class ServiceWithMemoryForEachUserExample {
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("ServiceWithMemoryForEachUserExample:UniqueChatRecallEvents").
            setCustomLabel("countingTokensUsed");

    interface Assistant {

        String chat(@MemoryId int memoryId, @UserMessage String userMessage);
    }

    public static void main(String[] args) {
        eventLogger.setJedis(new JedisPooledGetter(args).getJedisPooled()).initTS();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(OpenAiChatModel.withApiKey(APIKEYS.getDemoKey()))
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
        Scanner in = new Scanner(System.in);
        System.out.println("This is the first chat - chat session #1 - Please provide your name and hit enter:  ");
        String name = in.nextLine();
        assistant.chat(1, "Hello, my name is "+name);
        System.out.println("This is the second chat - chat session #2 - Please provide your name and hit enter:  ");
        name = in.nextLine();
        assistant.chat(2, "Hello, my name is "+name);
        System.out.println("\nChat #1 --> Asking the assistant to remind me of my name\n>>>"+assistant.chat(1, "What is my name?"));
        eventLogger.addEventToMyTSKey("What is my name?".length()/4);
        System.out.println("\nChat #2 --> Asking the assistant to remind me of my name\n>>>"+assistant.chat(2, "What is my name?"));
    }
}