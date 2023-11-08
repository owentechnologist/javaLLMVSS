package com.redislabs.sa.ot.jllmvss;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import redis.clients.jedis.JedisPooled;
import java.util.Map;
import java.util.Scanner;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;

public class EmbedNamedRedisHashAttributeAsVector {

    public static void main(String[] args)throws Throwable{
        JedisPooled jedis = new JedisPooledGetter(args).getJedisPooled();
        Scanner in  = new Scanner(System.in);
        String hash_key_name = "zoo:animal:11"; //zoo:animal:57 zoo:animal:31 zoo:animal:22
        String hash_attribute_name = "biography";
        System.out.println("Please enter the redis key-name of the zoo record you wish to target for vector embedding ["+hash_key_name+"]  :");
        hash_key_name = in.nextLine().trim().toLowerCase();
        System.out.println("OK - we know which hash to use\nPlease enter the text attribute stored in that key that you wish to target for vector embedding ["+hash_attribute_name+"]  :");
        hash_attribute_name = in.nextLine().trim().toLowerCase();
        String textToEmbed = jedis.hget(hash_key_name,hash_attribute_name);
        System.out.println("Text to embed has a length of: "+textToEmbed.length());
        // Create the embedding model
        Map<String, String> options = Map.of("maxLength", "768",  "modelMaxLength", "768");
        HuggingFaceTokenizer sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        long[] la = sentenceTokenizer.encode(textToEmbed).getIds();
        jedis.hset(hash_key_name.getBytes(), "embedding".getBytes(), longArrayToByteArray(la));
        System.out.println("done");
    }

}
