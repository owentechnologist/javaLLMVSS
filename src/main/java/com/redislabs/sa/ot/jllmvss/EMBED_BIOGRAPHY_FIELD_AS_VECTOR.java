package com.redislabs.sa.ot.jllmvss;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import redis.clients.jedis.JedisPooled;
import java.util.Map;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;

public class EMBED_BIOGRAPHY_FIELD_AS_VECTOR {

    public static void main(String[] args)throws Throwable{
        String zoo_animal_kay_name = "zoo:animal:11"; //zoo:animal:57 zoo:animal:31 zoo:animal:22
        JedisPooled jedis = new JedisPooledGetter(args).getJedisPooled();
        String biography = jedis.hget(zoo_animal_kay_name,"biography");
        // Create the embedding model
        Map<String, String> options = Map.of("maxLength", "768",  "modelMaxLength", "768");
        HuggingFaceTokenizer sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        long[] la = sentenceTokenizer.encode(biography).getIds();
        jedis.hset(zoo_animal_kay_name.getBytes(), "embedding".getBytes(), longArrayToByteArray(la));
        System.out.println("done");
    }

}
