
---
title: Type Variance in Java
date:  2017-05-27
---

Josh Bloch introduced me to type variance in *Effective Java*, but it was Martin Odersky who elucidated the bigger picture.  Type variance arises from the interplay between parametric polymorphism and subtype polymorphism.  Any language with both of these must have some story around type variance.

Scala appears to have a thorough story of type variance.  Java has only a partial story, mostly under the moniker "bounded wildcard type".

Besides being incomplete, Java's story of type variance also requires variance to be repeatedly defined at use sites, not once at declaration sites.  If I define an immutable collection class, I would like to declare that it's covariant in its element type.  Instead, I must remember to redefine the covariance when I'm using that type.

Bounded wildcard types in Java are a difficult subject to tackle, and I can't say that I've yet mastered it.  Indeed, it's easy for anyone to miss the opportunity for a bounded wildcard when writing a generic method.

Which brings me to the topic at hand.  I came across a limitation of this type signature, one of the `Collectors#groupingBy` overloads:

```java
public static <T, K, A, D>
Collector<T, ?, Map<K, D>> groupingBy(
            Function<? super T, ? extends K> classifier,
            Collector<? super T, A, D> downstream)
```

We can already see some effective use of bounded wildcard types, as well as the need for use-site repetition.  The classifier function is contravariant in its input type parameter `T` and covariant in its output type parameter `K`.  No surprises here.

But an opportunity appears to have been missed on the downstream collector.  The result type of the "grouping by" collector is a `Map<K, D>`.  This is a map which more or less reads and writes values of type `D`.  The values which are put into this map are being provided by the downstream collector.  But it's not necessary that the downstream collector provide exactly elements of type D.  It could provide elements of a subtype of D.

So I adapted the declaration as follows, and added my adapter to a utility class:

```java
public static <T, K, A, D>
Collector<T, ?, Map<K, D>> groupingBy(
            Function<? super T, ? extends K> classifier,
            Collector<? super T, A, ? extends D> downstream)
//                                  ^^^^^^^^^^^
```

I originally came across this limitation while using a custom downstream collector:

```java
public static <X> Collector<X, ?, ImmutableSet<X>> toImmutableSet() {
  return collectingAndThen(toSet(), ImmutableSet::copyOf);
}
```

Suppose for the sake of example that we define the following type:

```java
public interface Tuple<A, B> {
  A fst();
  B snd();
}
```

And then suppose I have a stream of these tuples:

```java
final Stream<Tuple<String, Integer>> namedDataPoints = ...;
```

It would seem reasonable to write this:

```java
final Map<String, Set<Integer>> dataPointsByName =
    namedDataPoints.collect(
        groupingBy(Tuple::fst, mapping(Tuple::snd, toImmutableSet())));
```

But the compiler won't allow this.  Because the declaration of `Collectors#groupingBy` accepts a downstream collector that is _invariant_ in its result type, the compiler rejects our code.  `Set<X>` and `ImmutableSet<X>` are not the same type, so `D` cannot be any type.

By making the downstream collector covariant in its result type, as shown above, the compiler resolves `D` to be `Set<X>` and permits a downstream collector whose result type is the subtype `ImmutableSet<X>`.

One could argue that my `toImmutableSet` collector should declare its result type as `Set<X>` rather than `ImmutableSet<X>`, but this isn't satisfying.  `ImmutableSet<X>` is a subtype of `Set<X>`.  I should be able to declare the more specific type and let the system upcast to ~~the more generic type~~ the *less* specific type.

One could argue the same point with the map declaration, the result of the reduction, but I would arrive at the same counterargument.

In short, type variance in Java is tough to get right.  Case in point: my `groupingBy` adapter seems to work, but am I *entirely confident* that it doesn't compromise type safety?  No, I'm not.
