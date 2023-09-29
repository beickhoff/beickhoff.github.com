
---
title: Typesafe Heterogeneous Containers in Golang
date:  2023-09-29
---

__The following is one implementation of Typesafe Heterogeneous Containers in Golang.__  Actually, it's more than one implementation because I leave open a few options.  So for those who don't know, what are Typesafe Heterogeneous Containers?


__Like an ordinary Go map, it's a key+value data structure.__

__Unlike a map, it can contain values of arbitrary types but with compile-time type checking.__  With ordinary Go maps, you don't get both.  If you want values of arbitrary types, you lose the compile-time type checking.  For example, `map[string]any` is going to require run-time type checks (type assertions).

__The trick is to parameterize the keys, not the container.__  And when I say "parameterize" here, I'm talking about type parameters.

__Let's model this as Key and Container.__  Note that I actually prefer the name "Map" instead of "Container", but the terminology would get confusing below.

__Each Key instance is a pointer to a generic Key struct.__  We use a pointer because it acts as a unique ID for each Key instance at run-time.  We use a generic struct because it allows type checking at compile-time.  The struct's type parameter names the type of the corresponding Value.

```go
type Key[V any] struct {
  mustBeNonempty uint8
}

// Example Keys

var Scheme   = &Key[string]{}
var Hostname = &Key[string]{}
var Port     = &Key[uint16]{}
var Payload  = &Key[[]byte]{}
```

Interestingly, the struct must be nonempty, even though we make no use of the field.  If the struct were empty, the runtime would only ever create a single instance shared by all instantiations, and the pointers would no longer be unique.  (Ask me how I know.)

A better use of the field might be to store the name of the Key as a string.  This would surely aid debugging or logging.

__The Container is backed by an ordinary map.__  That means we need some uniform type for map values (elements).  We even need an alternate type for map keys, because `*Key[V]` doesn't work.  It's only a type constructor, not a concrete type -- or what the Language Spec calls an instantiated type.

__Map Option 1:  An ordinary map of interface values to interface values.__  This is the natural choice in Go, although I have to admit I was surprised that Go even allows an interface type as the key type.

```go
type Container struct {
  m map[any]any
}
```

__Map Option 2:  An ordinary map of pointers to pointers.__  I mean raw pointers.  This approach is questionable at best, but as a historical note this is the first implementation I succeeded with.  Anecdotally, I found this variation to be somewhat faster.

```go
type Container struct {
  m map[unsafe.Pointer]unsafe.Pointer
}
```



__The basic read/write Container API cannot be defined as ordinary methods.__  Methods in Go cannot have type parameters, so the obvious constructions like `func (c *Container) Put[V](k *Key[V], v V)` are ruled out.  Obviously, other methods would be fine: `func (c *Container) Clear()`.

__API Option 1:  Define the read/write API as top-level functions.__

```go
func ContainerPut[V any](c *Container, k *Key[V], v V) { … }

func ContainerGet[V any](c *Container, k *Key[V]) (V, bool) { … }
```

__API Option 2:  Define the read/write API as methods on Key.__

```go
func (k *Key[V]) Put(c *Container, v V) { … }

func (k *Key[V]) Get(c *Container) (V, bool) { … }
```

Obviously, you can choose different names if you don't like "put" and "get": read/write, load/store, peek/poke, whatever.

Here's a complete example using Map Option 1 and API Option 1.  Think of this as a variation of `context.Context` with compile-time type checking.

```go
package main

type Key[V any] struct {
  _ uint8
}

type Container struct {
  m map[any]any
}

func ContainerNew() *Container {
  return &Container{
    m: make(map[any]any),
  }
}

func ContainerPut[V any](c *Container, k *Key[V], v V) {
  c.m[k] = v
}

func ContainerGet[V any](c *Container, k *Key[V]) (V, bool) {
  entry, found := c.m[k]
  var v V
  if found {
    v = entry.(V)
  }
  return v, found
}

// ----------------------------------------------------------

var Hostname = &Key[string]{}
var Port     = &Key[uint16]{}
var Verbose  = &Key[bool  ]{}

func main() {
  ctx := ContainerNew()

  ContainerPut(ctx, Hostname, "example.com")
  ContainerPut(ctx, Port    , 5432)
  ContainerPut(ctx, Verbose , true)

  {
    var hostname string
    var port     uint16
    var verbose  bool

    hostname, _ = ContainerGet(ctx, Hostname)
    port    , _ = ContainerGet(ctx, Port)
    verbose , _ = ContainerGet(ctx, Verbose)

    print("hostname = ", hostname, "\n")
    print("port     = ", port    , "\n")
    print("verbose  = ", verbose , "\n")
  }
}
```

Most Go programmers will balk at the format of the code, but it does run.  Style aside, the demo code doesn't look that amazing, does it?  So what's the big deal?

__The real win isn't the code you can write; it's the code you ***can't*** write.__

Imagine the following copy/paste error:

```go
    port, _ = ContainerGet(ctx, Hostname)
```

This is now caught at compile-time:

```txt
… cannot use ContainerGet(ctx, Hostname) (value of type string) as
uint16 value in assignment
```

If every call site needed to use a type assertion, these sorts of issues wouldn't be caught until run-time.

Writes get the same benefit:

```go
  ContainerPut(ctx, Hostname, 5432)
```

```txt
… cannot use 5432 (untyped int constant) as string value in argument
to ContainerPut
```

Typesafe Heterogeneous Containers are by no means a replacement for ordinary maps.  You still want ordinary maps, say about 97% of the time.  But the other 3% of the time, it's much better to have type checks at compile-time.




