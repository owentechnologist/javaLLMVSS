package com.redislabs.sa.ot.jllmvss;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import redis.clients.jedis.*;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

/**
 * Note this example borrows heavily from:
 * https://github.com/langchain4j/langchain4j-examples/blob/main/other-examples/src/main/java/ServiceWithToolsExample.java
 *
 **/
public class RedisSearchToolExample {
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("RedisSearchToolExample:AssistantChatEvent").
            setCustomLabel("countingTokensUsed");

    static class Search {
        JedisPooled jedis = null;
        static double tokenCount = 0;

        @Tool("provides the biography of a zoo animal once given species and knownDisorder as parameters")
        String fetchBiography(String species,String knownDisorder) {
            Query q = new Query("@species:{"+species+"} @known_disorders:("+knownDisorder+")").returnFields("biography")
                    .limit(0, 1);
            SearchResult sr = jedis.ftSearch("idx_zoo", q);
            String reply = String.valueOf(sr.getDocuments().get(0));
            tokenCount =reply.length()/4;
            return reply;
        }

        public Search setJedis(JedisPooled jedis){
            this.jedis = jedis;
            return this;
        }
    }

    interface Assistant {

        String chat(String userMessage);
    }

    public static void main(String[] args) {
        JedisPooled jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(OpenAiChatModel.withApiKey(APIKEYS.getDemoKey()))
                .tools(new Search().setJedis(jedis))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String question = "What is the fullName and dietary preferences of the gorilla whose disorders are described as none?";
        System.out.println("\nOur Assistant will use Redis to answer the following question: \n"+question+"\n");
        //Do the work of calling Redis and responding with a suitable and accurate answer:
        String answer = assistant.chat(question);
        eventLogger.addEventToMyTSKey(Search.tokenCount);
        System.out.println(answer);
    }
}
