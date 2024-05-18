package com.redislabs.sa.ot.jllmvss;

import java.util.Scanner;
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
 * mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis.mylocale.redisenterprise.cache.azure.net -p 10000 -s coolpassword -searchtool"
 **/
public class RedisSearchToolExample {
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("RedisSearchToolExample:AssistantChatEvent").
            setCustomLabel(TimeSeriesEventLogger.COUNTING_TOKENS_USED);
    static TimeSeriesEventLogger durationLoggerRedis = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("RedisSearchToolExample:RedisSearchEvent").
            setCustomLabel(TimeSeriesEventLogger.DURATION_IN_MILLISECONDS);
    static TimeSeriesEventLogger durationLoggerLLM = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("RedisSearchToolExample:LLMUsageEvent").
            setCustomLabel(TimeSeriesEventLogger.DURATION_IN_MILLISECONDS);

    static class Search {
        JedisPooled jedis = null;
        static double tokenCount = 0;

        @Tool("provides the biography of a zoo animal once given species and knownDisorder as parameters")
        String fetchBiography(String species,String knownDisorder) {
            long startTime = System.currentTimeMillis();
            Query q = new Query("@species:{"+species+"} @known_disorders:("+knownDisorder+")").returnFields("biography")
                    .limit(0, 1);
            SearchResult sr = jedis.ftSearch("idx_zoo", q);
            String reply = String.valueOf(sr.getDocuments().get(0));
            tokenCount =reply.length()/4;
            System.out.println("\nIt took "+(System.currentTimeMillis()-startTime)+" milliseconds to get the information from Redis...");
            durationLoggerRedis.addEventToMyTSKey((System.currentTimeMillis()-startTime));
            System.out.println("\nThis is the information retrieved from Redis that the LLM will use to construct it's response:\n"+reply+"\n");
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
        durationLoggerLLM.setJedis(jedis).initTS();
        durationLoggerRedis.setJedis(jedis).initTS();

        long startTime = System.currentTimeMillis();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(OpenAiChatModel.withApiKey(APIKEYS.getDemoKey()))
                .tools(new Search().setJedis(jedis))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
        System.out.println("\nLLMAssistant created... took: "+(System.currentTimeMillis()-startTime)+" milliseconds");
        durationLoggerLLM.addEventToMyTSKey((System.currentTimeMillis()-startTime));
        String disorder = "Bite";
        Scanner in = new Scanner(System.in);
        System.out.println("What animal disorder are you looking to match against eg: Bite  : ");
        disorder=in.nextLine().trim();
        String species = "Gorilla";
        System.out.println("What animal species are you looking to match against eg: Gorilla  : ");
        species=in.nextLine().trim();
        String question = "What is the fullName and dietary preferences of the "+species+" whose disorders are described as "+disorder+"?";
        System.out.println("\nOur Assistant will use Redis to answer the following question: \n"+question+"\n");
        //Do the work of calling Redis and responding with a suitable and accurate answer:
        System.out.println("\nCalling the LLM Assistant (which will use Redis Search to augment it's response...\n");
        startTime=System.currentTimeMillis();
        String answer = assistant.chat(question);
        durationLoggerLLM.addEventToMyTSKey((System.currentTimeMillis()-startTime));
        eventLogger.addEventToMyTSKey(Search.tokenCount);
        System.out.println("\nThis is the LLM response:\n\n\t"+answer);
    }
}
