---
title: Floating Point Fun
---

One day, a particular test failed spontaneously and yet succeeded on the next run.  Rooting out nondeterminism in tests is a favorite pastime of mine, so I went to work on this one.

The symptom of the failure was a numerical mismatch:

```txt
-   'weekly_total': 12.3,
+   'weekly_total': 12.299999999999999,
```

Immediately, one sees that floating point arithmetic is implicated here, but I was still surprised that this test failed only *sporadically*.  That total is a sum over five floating point numbers all of which are hard-coded into the test.  Could the representation of those numbers change per run of the test?  That didn't seem reasonable.  However, what could change is the *ordering* of the numbers.

Of the five floats, two were whole numbers and did not contribute to the problem.  Here are the remaining three numbers:

```python
numbers = [1.83, 2.8, 5.67]
```

Since the ordering could change, I thought I should compare the sums over every possible permutation.

```python
from itertools import permutations

def for_each_permutation(f):
  for permutation in permutations(range(len(numbers))):
    xs = list(map(lambda i: numbers[i], permutation))
    print '%s --> %s' % (xs, f(xs))

for_each_permutation(lambda xs: sum(xs, 0.0))

[1.83, 2.8, 5.67] --> 10.3
[1.83, 5.67, 2.8] --> 10.3
[2.8, 1.83, 5.67] --> 10.3
[2.8, 5.67, 1.83] --> 10.3
[5.67, 1.83, 2.8] --> 10.3
[5.67, 2.8, 1.83] --> 10.3
```

On the surface everything seems fine.  But the Python REPL is glossing over the critical details, evidently for readability.  We can see this using the `repr()` built-in:

```python
for_each_permutation(lambda xs: repr(sum(xs, 0.0)))

[1.83, 2.8, 5.67] --> 10.3
[1.83, 5.67, 2.8] --> 10.3
[2.8, 1.83, 5.67] --> 10.3
[2.8, 5.67, 1.83] --> 10.299999999999999
[5.67, 1.83, 2.8] --> 10.3
[5.67, 2.8, 1.83] --> 10.299999999999999
```

And the problem is revealed.  Sure enough, these sums are not equal:

```python
>>> sum([2.8, 1.83, 5.67], 0.0) == sum([2.8, 5.67, 1.83], 0.0)
False
```

Taking a step back, these values should never have been modeled as floats.  For the data we're trying to capture here, a rational number would have been best (`Fraction` in Python), though a `Decimal` would have sufficed.  I didn't have the luxury of changing the model at that moment, so the inputs were going to remain floats.  What I could change was the code that calculated the sum.

Because `Decimal` is so good at preserving the noise from floating point numbers, a straightforward conversion from `float` inputs to `Decimal` intermediates is not the cleanest option, although it does regain determinism:

```python
from decimal import Decimal

for_each_permutation(lambda xs: sum(map(Decimal, xs), Decimal(0)))

[1.83, 2.8, 5.67] --> 10.29999999999999982236431606
[1.83, 5.67, 2.8] --> 10.29999999999999982236431606
[2.8, 1.83, 5.67] --> 10.29999999999999982236431606
[2.8, 5.67, 1.83] --> 10.29999999999999982236431606
[5.67, 1.83, 2.8] --> 10.29999999999999982236431606
[5.67, 2.8, 1.83] --> 10.29999999999999982236431606
```

Instead, and somewhat surprisingly, we can again use the `repr()` built-in together with the `Decimal` constructor to get the numbers we really wanted.  Sadly, I have to use this pattern so often now that I've given it a shorthand, `D`.

```python
D = lambda x: Decimal(repr(x))

for_each_permutation(lambda xs: sum(map(D, xs), D(0)))

[1.83, 2.8, 5.67] --> 10.30
[1.83, 5.67, 2.8] --> 10.30
[2.8, 1.83, 5.67] --> 10.30
[2.8, 5.67, 1.83] --> 10.30
[5.67, 1.83, 2.8] --> 10.30
[5.67, 2.8, 1.83] --> 10.30
```

The moral of the story?  __Floating point addition is not commutative__.
