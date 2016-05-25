Atomic Updates in Knockout
==========================

Goals
-----

- Atomic mutation of several observables
- Minimal reevaluation of dependentObservables

API
---

Call ko.atomically, providing a callback.  Within the callback, mutations of observables will effectively happen atomically.

```javascript
ko.atomically(function () {
  anObservable("some_value");
  anotherObservable("some_other_value");
});
```

Reevaluation of dependentObservables will happen after the callback completes.  Furthermore, a given dependentObservable will be reevaluated at most once.

Use Cases
---------

- You need to update several observables, but you don't want dependentObservables (including bindings) to be reevaluated repeatedly throughout.  You want them to be reevaluated once, at the end.
- For consistency reasons or to maintain an invariant, you need to update more than one observable simultaneously.  Updating them sequentially would leave a gap of inconsistency.  This gap would be observed by the event-driven reevaluation of dependentObservables.
- Your graph of observables and dependentObservables contains undirected cycles, such as a diamond dependency.  You want to update one or more observables, but you want each dependentObservable to be reevaluated at most once.

Implementation
--------------

Obviously, we can't change the way client code uses observables and dependentObservables.  They are both still invoked as accessor/mutator functions.  However, the behavior of these functions must change during an atomic update.  We achieve this with the [State pattern](http://en.wikipedia.org/wiki/State_pattern).  Both observables and dependentObservables delegate portions of their implementation to an "observable manager".

We implement two observable managers: the default implementation, and the atomic implementation.  In order to define the responsibilities of an observable manager, we need several terms:

**The DAG**

The data dependency graph is a directed acyclic graph (DAG).  By convention, we'll use a data-flow direction for the edges, rather than a dependency direction.

* _Independent node_: a node in the graph that can be assigned a value; this is an observable
* _Dependent node_: a node in the graph whose value is evaluted from its dependencies; this is a dependentObservable

**Mutations**

* _Binding_, or _Rebinding_: assignment of a value to an independent node
* _Composite Mutation_: mutation of the current value of an independent node
* _Evaluation_, or _Reevaluation_: calculation and automatic assignment of a value to a dependent node

The stock example of composite mutation is with observableArrays.  Changes often happen directly on the underlying array, after which the system or the client code must call valueHasMutated.  The observable manager has no control over the mutation.  It does have control over the valueHasMutated invocation -- this is a type of broadcast.

**Broadcasts**

* _Broadcast_: notification that a node value has changed

Any mutation will trigger a broadcast.  We distinguish the three types of broadcasts:

* _Rebinding Broadcasts_
* _Composite Mutation Broadcasts_
* _Reevaluation Broadcasts_

Interestingly, composite mutation broadcasts can only be triggered on independent nodes even though the value of a dependent nodes could undergo internal mutation.

**Listeners**

The graph edges are based on subscriptions.  A subscription is created for a single node.  Its callback is invoked with the new node value whenever that value changes.  For dependentObservables, the new node value is disregarded.  Instead the dependencies are accessed directly, determining the shape of the graph dynamically.  This is good, but it means there are two fundamentally different types of subscriptions.  Since we are only interested in a subscription's callback, not the subscription object itself, we name these callbacks "listeners".  We distinguish the two types of listeners:

* _Managed Listeners_: the evaluate function of a dependentObservable; disregards its argument, so these can be invoked with no argument
* _Autonomous Listeners_: the callback function of a subscribe call; may require its argument, so these must be invoked with value of their subject

Transactions
------------

We (loosely) use the term "transaction" to describe the stateful machinery of an atomic update.  (Loosely, because rebindings are atomic and isolated, but composite mutations are not.)  A transaction separates two activies.  One is altering the state of independent nodes.  The other is intelligently cascading those changes to the rest of the graph.

A transaction goes through three phases and may repeat.  Hence, the atomic observable manager is itself stateful.

**Mutation Phase**

We establish the following observable manager state.  We then execute the provided callback.

- Rebindings are written to a cache.
- Consequently, rebinding broadcasts do not occur.
- Composite mutation broadcasts are intercepted.
- Reads of independent nodes return the cached value.  If no value has been cached, we fall back on normal operation.
- Writes to dependent nodes operate normally since they have no direct effect.
- Reevaluation broadcasts are discarded.  They are usually caused by other types of broadcasts, which we intercept.  They may also occur if a new dependent node is constructed.  These can be discarded because nothing is observing the new node.
- Reads of dependent nodes operate normally.

**Commit Phase**

Once the callback has completed, we silently commit the cached values.

1.  For each cached write, we (re)bind the independent node to the cached value.  A rebinding may have no effect.  For example, the current value and the written value might be the same.  An equalityComparer might dismiss such a rebinding.
2.  Broadcasts from successful rebindings are intercepted and interrogated for their targets.  Autonomous listeners are added to a set of deferred invocations, noting their subject.  Managed listeners are recorded as part of the "downstream" graph.  We transitively interrogate the subscriptions of managed listeners.  This builds the complete downstream graph and the set of peripheral autonomous listeners.
3.  Composite mutation broadcasts intercepted during the mutation phase are also interrogated.  They add to the downstream graph and set of autonomous listeners.

**Publication Phase**

With all transactional values commited to the independent nodes, we handle cascading changes.  We first establish the following observable manager state:

- Rebindings are intercepted.
- Consequently, rebinding broadcasts do not occur.
- Composite mutation broadcasts are intercepted.
- Reads of independent nodes operate normally.
- Writes to dependent nodes operate normally since they have no direct effect.
- Reevaluation broadcasts are discarded.  Atomic transactions exist to _manage_ the work that reevaluation and rebinding broadcasts normally do.
- Reads of downstream dependent nodes (lazily) trigger evaluation once.  Subsequent reads operate normally.
- Reads of non-downstream dependent nodes operate normally.

Then we perform the following:

1.  All downstream managed listeners are invoked.  This means all downstream dependent nodes are reevaluated.  One-shot, lazy evaluation ensures that evalution occurs in topological order.  This is true regardless of the dependency graph, which is determined dynamically.
2.  All deferred invocations of autonomous listeners are invoked, passing in the new value.  This happens after downstream reevaluation since a listener might be observing a dependent node.

Additional changes to independent nodes are not allowed during this phase.  If any rebinding occurs, it is intercepted and applied later.  Composite mutations cannot be intercepted, but their broadcasts are.  In the event of composite mutations, two dependent node evaluations may observe different composite values for a shared dependency.  This can only happen if an observer mutates the graph data during notification.  (See Edge Cases below for more about this.)

**Repetition**

At this point the transaction is complete.  If during publication any rebindings or composite mutation broadcasts are intercepted, they must be handled.  We repeat the cycle of mutate, commit, and publish using the intercepted mutations.

Consequences
------------

Enabling atomic updates requires several modifications to the code base:

- We give a unique identity to each dependentObservable's evalute function.  This is the only way to guarantee a single reevaluation during a transaction.  Strict equality (i.e. object identity) would work but would produce quadratic-time algorithms.
- Similarly, we must give a unique identity to each observable.  This is allows us to cache values during the Mutation Phase.
- We must create direct accessor/mutator methods for observables and dependentObservables.  This is how an observable manager is given unhindered access.
- We must be able to access a subscribable's subscriptions.  This is how we intercept and later handle rebinding broadcasts.
- We must be able to access an evaluate function's parent dependentObservable.  This is how we transitively follow the dependency graph.  Subscriptions themselves only point to evaluate functions.

Edge Cases
----------

There are pathological cases where an atomic update could upset application invariants.  Consider the following dependentObservable that alters its own dependency:

```javascript
B = ko.dependentObservable(function () {
  var value = A();
  if (<some condition>) {
    A(<some alternate value>);
  }
  return A();
});
```

Presumably, there is some invariant which the value of A must satisfy.  If unsatisfied, the conditional rebinding forces it.

Outside of atomic updates, the value of B is always based on an invariant-satisfying value of A.  This is because the conditional rebinding of A commits its new value.  The final read of A sees this new value.

In an atomic update, this publication-phase rebinding would be deferred.  During the first pass, the final read of A would still see the old value.  Hence, the value of B would be based on the invariant-violating value of A.  Only during the second pass would B see the new value of A.  Although both passes happen in immediate succession, there is a window of invariant violation.

This sort of construction is contrary to a normal DAG.  In Knockout, it would be impossible to expect reevaluations to be free of side effects.  (This is how DOM bindings work!)  But it is reasonable to expect dependent node reevalation to not alter upstream independent node values.

This advice is easily applied to our example.  Instead of altering A, B should return the invariant-satisfying transformation of A.  B then acts as a "normalizing" proxy for A.
