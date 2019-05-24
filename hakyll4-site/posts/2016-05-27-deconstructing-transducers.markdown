---
title: Deconstructing Transducers
---

Transducers are a relatively new tool in the Clojure toolbox.  A couple of posts on the Clojure Google Group clued me into my own ignorance of transducers.  So I decided to explore them.


## The Foundation: Incremental Processing

Understanding the domain of transducers starts with incremental processing.  Consider a step function, *i.e.* a function with the following signature:

<div style="text-align: center;">
    (acc, x) -> acc'
</div>

It starts with an initial value (a zero value) and incrementally consumes input.  Given the prior accumulated value and the next input, derive the next accumulated value.  A stream of inputs effectively becomes a stream of accumulated values.  Often only the last such accumulated value is interesting.

Where do the input values come from?  Values are pushed by an external process.  So our incremental processing model has two constituents: the step function, and the driving process which feeds it.  Many tasks can be modeled like this.


### Examples

Solutions to some tasks are easily formulated with this model.  (Easily, not necessarily optimally.)

- Calculating the sum of a collection of numbers
- Finding the maximum element in a collection
- Transferring elements from one collection to another

The input values are the elements of a collection.  The driving process iterates over the collection, calling the step function with each element, while threading the accumulated value stream throughout.

How about this task:

- Printing the elements of a collection to standard output

The driving process and input source have stayed the same, but the step function has been weakened.  The accumulated value stream no longer serves a purpose since we're only inducing side effects.  Whereas the prior three examples were *computational*, this one is *effectual*.

Similar tasks can be modeled for terminating lazy sequences.  The driving process is unchanged.

- Finding whether a given element occurs in the sequence
- Realizing the sequence (*i.e.* `doall`)

Instead assume a nonterminating lazy sequence.  Because the driving process is synchronous it will never naturally terminate.  The task can only complete if the step function triggers early termination.

- Transferring the first *n* elements of a nonterminating sequence into a vector

How does the step function know when to terminate?  Because a vector records its own length, the step function can identify the terminal condition using only its first argument (the accumulated value).  But such a step function takes on two responsibilities:

1.  It conjoins elements to the vector.
2.  It measures the vector and terminates the process early based on the predetermined value of n.

Can these responsibilities be decomposed?  Furthermore, can we generalize nth-element-termination to work with a noncountable accumulated value, *e.g.* by tracking the number of input elements?  This is possible, but requires the step function to record state internally.

So far, our input elements have come from collections or sequences.  (The curious may want to see some [source code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/src/beickhoff/deconstructing_transducers/examples.clj) or [test code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/test/beickhoff/deconstructing_transducers/examples_test.clj).)
  But this incremental processing model can fit other circumstances.


### More Examples

Now suppose that the input elements are events in a functional reactive system.  The driving process could be an event loop, delivering input asynchronously.

- Deriving the next state of the system by ingesting input events

What about this one:

- Debouncing events

We could easily augment our step function to include leading-edge debouncing, assuming that it keeps internal state.  But we could not as easily provide trailing-edge debouncing.  Why?  A step function is reactive, not proactive.  It's invoked by the driving process when input arrives.  It can easily stop leading-edge propagation of input, but it cannot later induce trailing-edge propagation.  Approaches to achieve this could be applied -- scheduling a future task, for example -- but they would work outside the incremental processing model.

Now consider values being put onto a channel.  Perhaps surprisingly, we can still employ the incremental processing model.  The driving process is now the unification of the otherwise uncoordinated work of value producers.  The step function becomes effectively attached to the channel, its principal goal being the propagation of values onto the channel.  This is effectual, not computational, so again we're forgoing the accumulated value stream.

- An adapter that transforms every value placed onto the channel
- A filter that propagates only those channel inputs which satisfy a predicate
- A gauge that reports metrics about values placed onto the channel
- A gate that opens or closes a channel when a particular element arrives

Imagine the step functions that implement these behaviors.  Each has two responsibilities.  It propagates appropriate values onto the channel, but it's also responsible for the remaining behavior: translation, selection, observation, and gating, respectively.  How can we decompose these?



## The Superstructure: Decomposing The Step Function

Transducers give us a mechanism to decompose the step function.  Put another way, transducers allow us to decorate an existing step function.

Let's revisit one of the examples from above which begged decomposition.

- Transferring the first *n* elements of a nonterminating sequence into a vector

To begin, we'll pick a step function with a single responsibility: conjoining elements to the vector (literally our step function is just `conj`).  Then we'll decorate that step function with an additional responsibility: nth-element-termination.

Let's build this decorator inside-out.  Our goal is to create a step function.

```clojure
(fn [acc x]
  ...)
```

That step function decorates another step function.  So the process for building the new step function takes an original step function as a parameter.

```clojure
(fn [original-step-fn]
  (fn [acc x]
     ...))
```

The base case is simply delegating to the original.

```clojure
(fn [original-step-fn]
  (fn [acc x]
    (original-step-fn acc x)))
```

At this point, it's worth noting that we've effectively<sup>&#42;</sup> written a transducer.  (<sup>&#42;</sup>Experienced readers may object regarding missing arities.  This will be addressed later.)

We also need to terminate the incremental processing early.

```clojure
(fn [original-step-fn]
  (fn [acc x]
    (if we-have-not-yet-propagated-n-values
      (original-step-fn acc x)
      (reduced acc))))
```

Unlike before, we'll make no assumptions that the accumulated value grows with each step, nor that it is countable, nor even that the task is computational.  Instead we must internally track the number of elements we've propagated.  That means our decorated step function will be stateful.  This seemingly reckless introduction of state ought to concern you, but for now let's continue.  The end may justify the means.

```clojure
(fn [original-step-fn]
  (let [counter (volatile! 0)]
    (fn [acc x]
      (let [i (vswap! counter inc)]
        (if (<= i some-predetermined-limit)
          (original-step-fn acc x)
          (reduced acc))))))
```

This is the __take n__ transducer, though we still need to parametrize the "predetermined limit" of n.  Ultimately, our top-level function is not a transducer itself but rather a transducer constructor.

```clojure
(defn taking [n]
  (fn [original-step-fn]
    (let [counter (volatile! 0)]
      (fn [acc x]
        (let [i (vswap! counter inc)]
          (if (<= i n)
            (original-step-fn acc x)
            (reduced acc)))))))
```

At last, we can build the necessary step function out of reusable, single-responsibility pieces:

```clojure
((taking n) conj)
```


### State

The introduction of mutable state should not be taken lightly.  What has it bought us?  The transducer built by our `taking` function is independent of context.  It works with any types of input values and accumulated values.  It works with any delegate step function.  It works for both computational and effectual tasks.

What has it cost us?  The decorated step function -- the one that the transducer returns -- is impure.  Its behavior changes.  Call it twice with the same arguments and you won't necessarily get the same result.  It cannot be shared or reused.  It has a shelf life and must be disposed of in the proper time.  Such impure functions have their place, but this kind of function should be the exception, not the norm.

We must be very careful with such a function.  The extent of its effects must be limited to the driving process; it must not escape, either directly or indirectly.  It cannot be shared by multiple threads, but it must be safe to transfer between threads (think memory visibility).

There is another wrinkle.  Internally accumulated state may need to be flushed.  Which finally brings us to a long-expected correction.


### Reducing Functions

We've been talking this whole time about step functions.  In fact, we need to be talking about reducing functions.  In Clojure, reducing functions have three arities.

* the step arity: `[acc x]`
* the completion arity: `[acc]`
* the init arity: `[]`

So the step function we've been talking about is really just the two-parameter arity of a full-fledged reducing function.  The completion arity is what allows us to flush state.  It's a signal to the reducing functions that the driving process is terminating.  There will be no more input.

Truth be told, I do not understand the init arity.  In fact, my entire exploration into transducers started because I, like others, [did not understand the init arity](https://groups.google.com/d/topic/clojure/HK9LkmlRyjY/discussion).  I still do not.  As near as I can tell, it serves little purpose.



## The Sum Total

As stated previously, the domain of transducers is the domain of the incremental processing model.  The incremental processing model is

1. a set of input elements
2. a reducing function which consumes the input elements, and
3. a driving process which feeds the reducing function, having some computational or effectual goal.

We can cast actors into these roles piecemeal.  For example,

* `eduction` fixes only the source of input elements and the initial decoration of the reducing function.  The driving process and the rest of the reducing function are deferred.
* Applying a transducer to a channel fixes the driving process and the reducing function.  The source of input elements is deferred.
* `into` requires every role to be filled.

Within this model there are several variables (not necessarily independent):

- Time:  The driving process can act synchronously or asynchronously.
- Availability of input:  The set of input elements could be totally present or only partially present.  In particular, the set of input elements could be unlimited.
- Termination:  The process could terminate naturally, terminate early, or continue indefinitely.  Early termination is triggered by the step function rather than the driving process.
- Goal:  The overall task can be computational or effectual.  Using this model for effectual tasks amounts to weakening the role of the accumulated value.  The step function becomes (arguably) an overdressed unary function.
- State:  The overall task can be internally stateful or stateless.

There are also some invariants:

- Transducers are reactive, meaning that a value must be pushed by a transducing process for a transducer to act upon it.  This makes a process with push semantics a natural fit for this model.  A process with pull semantics is possible but requires buffering.  (This is the case with `sequence` and channels.)
- Transducers are synchronous, meaning that a transducer can act upon a value only when one is pushed to it by the driving process.  It cannot induce a value at its own time.  However, when given a single value [it can propagate more than one](https://groups.google.com/d/topic/clojure-dev/9Ai-ZuCezOY/discussion) and does so synchronously.



## Exploration

Given everything we know, let's try modeling some tasks with our incremental processing model.  This is exploratory work, so expect some flops.


### Word Count Transducer

What about term frequency / word count?  The input values could be words or lines from a corpus of text.  The output could be the top ten most frequent terms.  But there's a problem.  No output can be produced until we've consumed the entire corpus of text.  Because of this, incremental processing is an awkward overall fit.

Initially, I deemed this attempt a dud.  However, after some prototyping, I realized this problem could be divided into two phases: (1) accumulating the table of word frequencies, and (2) emitting data from that table.  Modeling each phase then becomes straightforward.  Our approach is reaffirmed by the use of `(remove string/blank?)` and the natural addition of something like `(remove stop-words)`.

Connecting the phases was less clear, though I feel like there is an interesting idea here worth behammocking: hooking into the completion phase of transduction.

```clojure
(transduce                 ; phase 1
  (comp
    split-words
    (remove string/blank?)
    element-frequencies
    (upon-completion       ; connecting the two phases
      (fn [freqs]
        (into []           ; phase 2
          (comp
            (take 10)
            (map key))
          (sort-by val descending freqs)))))
  nil
  nil
  some-corpus-of-text)
```

(The curious may want to see the [source code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/src/beickhoff/deconstructing_transducers/word_count.clj) and the [test code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/test/beickhoff/deconstructing_transducers/word_count_test.clj).)


### JSON Parsing Transducer

What about parsing an unlimited stream of JSON values?  I've made the input values arbitrary blocks of ASCII text.  When a value is parsed, it is propagated to the delegate reducing function.

```clojure
(def json-channel (a/chan 1 (comp lexer parser)))

(>! json-channel (byte-buf<-string "{\"success\": true}")))
; => {"success" true}
```

This example is more interesting and seems to fit the incremental processing model, particularly as a channel transducer.  But it's still unclear whether there is a seed of a good idea here.

(Only the brave dare gaze upon the [source code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/src/beickhoff/deconstructing_transducers/json.clj) and the [test code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/test/beickhoff/deconstructing_transducers/json_test.clj).)


### Bowling Score Transducer

What about calculating a bowling score?  The input values could be pin counts for individual throws.  Regarding output, we could design this a couple of different ways.  This could be computational, synchronously processing all throws and yielding a scoreboard, *i.e.* a set of ten frames with throws and scores.  This could also be effectual, synchronously or asynchronously processing throws, propagating frame scores as they are determined.  I opted for the latter, partly for simplicity.

```clojure
(transduce (comp frame-scores cumulative-score) printing nil
           [10, 0 10, 2 3, 7 1, 9 1, 6 3, 0 0, 9 0, 10, 10 10 3])
20
32
37
45
61
70
70
79
109
132
```

(Those remaining may want to see the [source code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/src/beickhoff/deconstructing_transducers/bowling.clj) and the [test code](https://github.com/beickhoff/beickhoff.github.com/blob/5921254878f441234dcd585cadbf065bf2a0b11e/code/2016-05-27-deconstructing-transducers/test/beickhoff/deconstructing_transducers/bowling_test.clj).)


### Retrospection

We've written these as transducers because we could.  But should we?  Why opt to write a transducer-based solution?  After all, it has an impact on our code.  Many of the functions have to be written as impure multi-arity reducing functions.  What's the upshot?

There are two principal benefits of writing a transducer-based solution.  First, your transducers can be used in many contexts.  Second, you gain access to the existing transducers in clojure.core and elsewhere.  Transducers compose.  No need to rewrite `map`, `filter`, `take`, `drop`, `dedupe`, and all the rest.  Those come for free, not only for the library author but more importantly for the library consumer.

The transducer-based implementations of `map`, `filter`, &c. have another advantage.  They are stack-based rather than seq-based.  Intermediate garbage is minimized.

The word count solution was able to advantageously leverage core transducers.  Connecting two distinct incremental processing phases still needs work.

The JSON parsing transducer did not leverage any core transducers.  Furthermore, client code stands to gain little in the way of composition with core transducers.  The garbage generated during parsing would far outweigh any garbage savings from a subsequent use of `filter`, for example.

The bowling score transducer also did not leverage any core transducers.  Client code could (conceivably) compose these with core transducers, though a use case for this is not immediately clear.

Are any of these transducers reusable?  Doubtful.  Better transducers would be reusable, like `map` and `filter`.  Still, like good design, I suspect that reusable transducers are usually discovered, not invented.  Better to explore transducers by writing limited-use implementations than to postpone for perfection.


## Next Steps

Try to find tasks that can be modeled as incremental processing.  See if you can implement these using existing transducers.  See if it suggests the possibility of new transducers.  Even if your effort yields unsatisfying results, you'll have honed your intuition about transducers.
