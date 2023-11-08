This example Java code shows the use of langchain4j as a library that enables interactions with popular Large Languages Models as well as the use of huggingFace libraries for embedding vectors.

It expects that you have a running instance of Redis Enterprise (available for free in the cloud or as a try-it-out download from redis.com)
This instance of the Redis Database needs to have the Search, Time Series and Bloom modules installed in order to function with all the code in this repository.

A sample Tool is shown that uses RediSearch as a means to retrieve needed information.

A chat-bot demonstrates holding onto two different sessions - remembering user names.

The Main class accepts arguments that either run logic it holds that interacts with an LLM, or fires off one of teh other Classes in the package that demonstrates something related to embedding vectors and searching etc.

This code uses JedisPooled for the connection to Redis which allows all operations you might want - including the use of TimeSeries for accurate metrics and trend analysis as well as Probabilistic data structures such as CuckooFilters and TopK which allow for de-duping and lightweight-estimated counting/ranking.
Some information on starting the program is given at the top of the Main class - it is also below:

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
     * directional: -vsssemanticcache (NB: this is not implemented as of 2023-10-23)
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password"
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-testmemory"
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -simplellmcache"



#### * Note that you need to execute some commands against your instance of redis to load data and create search indexes
The first set are designed to populate Redis with some mock data and a Search index that can be used to demonstrate VSS and other search behaviors.
#### These first commands create some zoo animal data stored in hashes and also create a search index on these zoo animals
#### * you should run all the commands found in this file:

```
/resources/redis_commands_zoo.txt
```

#### ^ These can be executed by copy-pasting them into redis-cli and/or the RedisInsight UI.

#### *Other*  commands used by additional llm- and search related code examples are stored in the file:

```
/resources/redis_commands_non_zoo_search_indexes.txt
```


To run a vector similarity search example that queries the zoo animal data use:  
```-vectorsearch```

as in:
```
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -vectorsearch" 
```

^ this -vectorsearch example executes 3 quite reasonable queries that initially completely guess at matching data because the hash objects in Redis holding the animal data do not yet have vector embeddings stored in them.

Running the -vectorsearch before creating and storing the embeddings will still offer results.  You are likely to see results like this:
``` 
SEARCH RESULT from comparing this sentence:
        India is the home of this animal

```

As you can see, with no vector embeddings the query matches nothing

#### * To make successful use of the vector similarity search, vector embeddings need to be loaded into Redis into each Hash
#### There is a way to accomplish this, slowly, pedantically, by executing
``` 
-textembedding
```
as in:

``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -textembedding" 
```
This ^ will ask you to specify the key in redis that you wish to enrich by adding a vector embedding of a text attribute

(There are 4 animals stored as Hashes, so it will take you 4 runs of the program to do this)
* zoo:animal:57
* zoo:animal:31
* zoo:animal:22
* zoo:animal:11

For each of them, you will want to create embeddings for the

```biography```

attribute.

Once you have created the embeddings you can re-execute the -vectorsearch and see better results!
``` 
SEARCH RESULT from comparing this sentence:
        India is the home of this animal

        id:zoo:animal:57, score: 1.0, properties:[biography=Sam Sneezy: The Indian Tiger's Journey ...
```

* Notice that:  -vectorsearch does not utilize an LLM to generate a response, but merely fetches the zoo animal data stored in the Hash object in Redis and returns it in full as a response.

.
#### A different example uses a redis search as a way to augment the LLM's knowledge dynamically - this example uses the directive:   -searchtool
The flow of the

```
-searchtool
```

example is to load relevant data based on a dynamically constructed non-VSS search query and pass the interesting information as a prompt to the LLM to use as a deliberate context so that it will provide information in its response specific to the matched information.  I this case = one of the zoo animals biographies.

This pattern could be applied to SQL queries or simple key lookups as well - just make a tool that does those things!

#### Yet another Example:
```
-testmemory
``` 

under the covers - calls:

```
ServiceWithMemoryForEachUserExample.java
``` 

and shows the use of application layer memory being used to augment the interactions with an LLM - this management of the memory is done through constructs provided in the langchain4J library.  Such memory is not shared across client processes as it is not stored in Redis.

It is relatively simple to implement a more durable and expandable memory solution using several of the datatypes in Redis - a popular choice for this purpose is the List datatype.


Note that most of the examples have Jedis/redis calls that utilize Topk and Timeseries  capabilities available in Redis Enterprise on Azure and GCP and AWS as well as if you install the software and manage it yourself.  These additional datatypes allow for useful analytics and quick understanding of trends and popular / top requests and such.

#### * You can call commands against Redis that provide the insights captured by these datatypes -

Refer to the redis commands shown in the

```
redis_commands_queries.txt
``` 

file  - be aware... the TimeSeries and TOPK commands may not yield useful results until you run several executions of various directives so as to populate Redis with Search, TimeSeries, and TopK data.

Note that -vsssemanticcache calls VSSSemanticCachedLLMExchange.java --> an unfinished bit of code left as an exercise for newcomers to the langchain4J and Redis / Jedis libraries.

The ExpertKneeAdvisor and BiographyExpert are both in need of some data and prompt tweaking.  The current interface allows for a very limited set of possible prompts and would be better impemented with a topk listing (and retrieval as suggestions) of popular questions for such an FAQ.  The responses to such popular prompts would then be cached and could be search using regular search (which would be deterministic at the expense of reusability), or by using embeddings of the prompts for hybrid VSS and content filtering by rating - similar in fashion to the example shown in VSSSemanticCachedLLMExchangeComplete.java

There is a lot that can be accomplished with this technology stack!