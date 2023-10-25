package com.redislabs.sa.ot.jllmvss;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import redis.clients.jedis.JedisPooled;

import java.util.Scanner;

import static java.time.Duration.ofSeconds;

/**
 * To make this go you would use a command like this:
 *  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s p@ssword333 -simplellmcache"
**/
public class CachedLLMExchange {

    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("SimpleCache:GetAResponseEvent").
            setCustomLabel("countingTokensUsed");
    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger();//use default topkKeyName
    static JedisPooled jedis = null;
    static Scanner in = new Scanner(System.in);

    ChatLanguageModel model = null;

    public static void main(String[]args){
        jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        topKEntryLogger.setJedis(jedis);

        CachedLLMExchange cachedLLMExchange = new CachedLLMExchange();
        String userResponse = "";
        while(!userResponse.equalsIgnoreCase("end")){
            cachedLLMExchange.displayAndUpdateResponse();
            System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t" +
                    "To end this program type: 'end' and hit enter, " +
                    "to get another LLM response, just hit enter...  ");
            userResponse = in.nextLine();
        }
        System.out.println("\n\n\tEnding program...");
    }

    ChatLanguageModel getLLMModel(){
        if(null==model) {
            model = OpenAiChatModel.builder()
                    .apiKey(APIKEYS.getDemoKey())//APIKEYS.getOPENAIKEY()
                    .maxTokens(30)
                    .timeout(ofSeconds(60))
                    .build();
        }
        return model;
    }

    void displayAndUpdateResponse(){
        System.out.println("My LLM model is - " + getLLMModel());
        System.out.println("Write one of these words: 'rhyme' or 'joke' or 'why' and hit enter:  ");
        String action = in.nextLine();
        System.out.println("Provide a single word topic and hit enter:  ");
        String topic = in.nextLine();
        String response = "";
        topKEntryLogger.addEntryToMyTopKKey("topic: "+topic+" action: "+action);
        long startTime = System.currentTimeMillis();
        long endTime = 0l;
        if(!satisfiedByCache(topic,action)) {
            response = getResponses(topic,action);
            endTime=System.currentTimeMillis();
            System.out.println("\n************** RESPONSE ********************\n");
            System.out.println(response);
            System.out.println("\nPlease rate the last response given above ^ on a scale of 1-10 with 10 being the best." +
                    " \n(Enter a digit from 1 to 10 then hit enter):  ");
            int rating = Integer.parseInt(in.nextLine().trim());
            //add response to cache in Redis:
            // //(uses Hash datatype to allow for additional fields and search indexing)
            jedis.hset("llm:exchange:"+topic+":"+action,"response",response);
            jedis.hincrBy("llm:exchange:"+topic+":"+action,"userRating",rating);
        }else{
            endTime=System.currentTimeMillis();
        }
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
    }

    boolean satisfiedByCache(String topic,String action){
        boolean isSatisfiedByCache = false;
        String response = "Nothing cached - Need to call LLM...\n";
        if(jedis.exists("llm:exchange:"+topic+":"+action)){
            response = jedis.hget("llm:exchange:"+topic+":"+action, "response");
            isSatisfiedByCache=true;
            System.out.println("\n************** RESPONSE ********************\n");
        }
        System.out.println(response+"\n");
        return isSatisfiedByCache;
    }

    String getResponses(String topic,String action){
        String response = "";
        if (action.equalsIgnoreCase("rhyme")) {
            response = "" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_RHYMES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else if (action.equalsIgnoreCase("joke")) {
            response = "" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else {
            String question =
                    model.generate(
                            PROMPTS.YOU_ARE_A_TODDLER_BOT.apply(topic).text());
            response = "question :"+question+"\n\n" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_KIND_BOT_WHO_ANSWERS.apply(question).text());
            eventLogger.addEventToMyTSKey((topic.length() / 4) + (question.length() / 4));
        }
        return response;
    }
}
