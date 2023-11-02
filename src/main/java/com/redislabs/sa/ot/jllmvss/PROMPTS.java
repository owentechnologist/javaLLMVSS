package com.redislabs.sa.ot.jllmvss;
import dev.langchain4j.model.input.PromptTemplate;

public class PROMPTS {
    public final static PromptTemplate YOU_ARE_A_BOT_WHO_RHYMES=
            PromptTemplate.from("You are a bot who provides 16 word rhymes on command. " +
                    "Provide a 16 word rhyme using {{it}}");
    public final static PromptTemplate YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES=
            PromptTemplate.from("You are a bot who provides 16 word punchlines to dad jokes.  " +
                    "Provide a 16 word punchline to a dad joke about {{it}}");
    public final static PromptTemplate YOU_ARE_A_TODDLER_BOT=
            PromptTemplate.from("You are a 3 year old who asks why questions. Using 16 words " +
                    "ask a 16 word why question using {{it}}");
    public final static PromptTemplate YOU_ARE_A_KIND_BOT_WHO_ANSWERS=
            PromptTemplate.from("You are a bot who provides 16 word answers to simple questions on command. " +
                    "Provide a 16 word answer to this question: {{it}}");

    public final static PromptTemplate YOU_PROVIDE_CONTEXT=
            PromptTemplate.from("You are a patient with a knee problem. Ask a 16 word question starting with why about this content: {{it}}");
    public final static PromptTemplate YOU_ARE_AN_EXPERT_IN_KNEE_ISSUES=
            PromptTemplate.from("You are a physical therapist who gives your advice on painful knee conditions. " +
                    "What can you say to this: {{it}}");
    public final static PromptTemplate YOU_COMPLETE_WHAT_YOU_START=
            PromptTemplate.from("You are knowledgeable and kind and you complete unfinished statements. Refine this statement: {{it}} ");
    public final static PromptTemplate YOU_ARE_AN_EXPERT_IN_BIOGRAPHIES= PromptTemplate.from("You are a biographer. Share your knowledge about {{it}}  If you do not know, say so.");
    public final static PromptTemplate YOU_ARE_CURIOUS_ABOUT_BIOGRAPHIES= PromptTemplate.from("You are curious about individuals and their backgrounds. Submit a 16 word question starting with: Tell me about the individual who could be referred to using this information: {{it}}");

}
