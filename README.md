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

There are some sample redis commands in the resources folder in the file: redis_commands.txt

These can be executed from redis-cli and/or the RedisInsight UI.

The first set are designed to populate Redis with some mock data and a Search index that can be used to demonstrate VSS and other search behaviors.
The directive that interacts with the Animals in the zoo is -vectorsearch 
^ this -vectorsearch example executes 3 quite reasonable queries that successfully find relevant information from the biographies of the animals in the zoo.
It does not utilize an LLM to generate a response, but merely fetches the zoo animal data stored in the Hash object in Redis and returns it in full as a response.

Another interesting example uses a redis search as a way to augment the LLM's knowledge dynamically - this example uses the directive:   -searchtool
The flow of this -searchtool example is to load relevant data based on a dynamically constructed non-VSS search query and pass the interesting information as a prompt to the LLM to use as a deliberate context so that it will provide information in its response specific to the matched information.  I this case = one of the zoo animals biographies.

-localembedding fires off EmbedBiographyFieldAsVector.java which uses biography information already in Redis as the input to the call to a local huggingface library which then creates a byte[] embedding from the text and stored it as the 'embedding' field in that same hash object.  It will overwrite any existing embedding field.

-testmemory calls ServiceWithMemoryForEachUserExample.java and shows the use of application layer memory being used to augment the interactions with an LLM - this management of the memory is done through constructs provided in the langchain4J library.  Such memory is not shared across client processes as it is not stored in Redis.

Note that most of the examples have Jedis/redis calls that utilize Topk and Timeseries  capabilities availeble in Redis Enterprise on Azure and GCP and AWS as well as if you install the software and manage it yourself.  These additional datatypes allow for useful analytics and quick understanding of trends and popular / top requests and such.

Referring back to the redis commands shown in the redis_commands.txt file  - be aware... the TimeSeries and TOPK commands may not yield useful results until you run several executions of various directives so as to populate Redis with Search, TimeSeries, and TopK data.

Note that -vsssemanticcache calls VSSSemanticCachedLLMExchange.java --> an unfinished bit of code left as an exercise for newcomers to the langchain4J and Redis / Jedis libraries.

The ExpertKneeAdvisor and BiographyExpert are both in need of some data and prompt tweaking.  The current interface allows for a very limited set of possible prompts and would be better impemented with a topk listing (and retrieval as suggestions) of popular questions for such an FAQ.  The responses to such popular prompts would then be cached and could be search using regular search (which would be deterministic at the expense of reusability), or by using embeddings of the prompts for hybrid VSS and content filtering by rating - similar in fashion to the example shown in VSSSemanticCachedLLMExchangeComplete.java 

There is a lot that can be accomplished with this technology stack!