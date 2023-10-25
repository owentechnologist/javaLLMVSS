package com.redislabs.sa.ot.jllmvss;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

import static java.time.Duration.ofSeconds;


public class Main {

    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("Main:GetAResponseEvent").
            setCustomLabel("countingTokensUsed");
    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger();//use default topkKeyName
    /**
     * Optional args are additional to the other redis-based args and are needed in certain circumstances (if redis has a password)
     * Directional args tell the Main program what to do.
     * Only one Directional Arg is expected to be called per execution of the Main program
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000"
     * Expects -h host and -p port
     * optional: -s password
     * optional: -u username
     * directional: -testmemory
     * directional: -searchtool
     * directional: -localembedding
     * directional: -vectorsearch
     * directional: -simplellmcache
     * directional: -expertkneeadvisor
     * directional: -vsssemanticcache (NB: this is not implemented as of 2023-10-23)
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password"
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-testmemory"
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -simplellmcache"
     * @param args
     */
    public static void main(String[] args)throws Throwable{
        ArrayList argsList = new ArrayList(Arrays.asList(args));

        if(argsList.contains("-testmemory")){
            System.out.println("special memory test bot...");
            try {
                ServiceWithMemoryForEachUserExample.main(args);
            }catch(Throwable tt){}
        }else if(argsList.contains("-searchtool")) {
            System.out.println("Redis Search tool...");
            try{
                RedisSearchToolExample.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else if(argsList.contains("-localembedding")){
            System.out.println("Local Embedding test ...");
            try{
                EmbedBiographyFieldAsVector.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else if(argsList.contains("-vectorsearch")){
            System.out.println("Vector Search Example ...");
            try{
                VectorSearchExample.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else if(argsList.contains("-simplellmcache")){
            System.out.println("Simple LLM Cached Responses Example ...");
            try{
                CachedLLMExchange.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else if(argsList.contains("-expertkneeadvisor")){
            System.out.println("Expert Knee Advisor Responses Example ...");
            try{
                ExpertKneeAdvisor.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else if(argsList.contains("-vsssemanticcache")){
            System.out.println("VSS Semantic Cached Responses Example ...");
            try{
                VSSSemanticCachedLLMExchange.main(args);
            }catch(Throwable x){x.printStackTrace();}
        }else{
            //execute just this Main class's logic...
            JedisPooled jedis = new JedisPooledGetter(args).getJedisPooled();
            Main main = new Main();
            eventLogger.setJedis(jedis).initTS();
            topKEntryLogger.setJedis(jedis);
            System.out.println(main.getResponses());
        }
    }

    String getResponses() {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(APIKEYS.getDemoKey())//APIKEYS.getOPENAIKEY()
                .maxTokens(30)
                .timeout(ofSeconds(60))
                .build();
        System.out.println("My LLM model is - " + model);
        Scanner in = new Scanner(System.in);
        System.out.println("Write one of these words: 'rhyme' or 'joke' or 'why' and hit enter:  ");
        String action = in.nextLine();
        System.out.println("Provide a single word topic and hit enter:  ");
        String topic = in.nextLine();
        String response = "";
        topKEntryLogger.addEntryToMyTopKKey("topic: "+topic+" action: "+action);
        if (action.equalsIgnoreCase("rhyme")) {
            response = "\n model response to " + topic + "  is:\n\n" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_RHYMES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length()/4);
        } else if(action.equalsIgnoreCase("joke")){
            response = "\n model response to " + topic + "  is:\n\n" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length()/4);
        } else{
            String question =
                    model.generate(
                            PROMPTS.YOU_ARE_A_TODDLER_BOT.apply(topic).text());
            response = "\n model response to the question: '" + question + "'  is:\n\n" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_KIND_BOT_WHO_ANSWERS.apply(question).text());
            eventLogger.addEventToMyTSKey((topic.length()/4)+(question.length()/4));
        }
        return response;
    }
}