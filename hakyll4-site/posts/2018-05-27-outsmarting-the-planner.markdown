---
title: Outsmarting The Planner That Outsmarted Me
---

I'm a software engineer, not a data analyst.  Nevertheless, I've had my fair share of experience reading and writing *escuelle*.  And there's this trend that I've seen.  Just when you think it's slowly going away ([MapReduce](https://static.googleusercontent.com/media/research.google.com/en//archive/mapreduce-osdi04.pdf)), it comes back ([CQL](https://issues.apache.org/jira/browse/CASSANDRA-1703)).  Time ([Hive](https://hive.apache.org/)) and time again ([Presto](https://prestodb.io/)).

I'm not fundamentally opposed to escuelle.  I use it all the time.  But I don't consider the language itself to be one of great merit.  It has its downsides ([SQL Injection](https://www.owasp.org/index.php/SQL_Injection)).  And it's been at least implicated in one of the great crimes against software ([ORMs](https://en.wikipedia.org/wiki/List_of_object-relational_mapping_software)).

I suppose it's like HTML.  It's not merit that keeps it around.  It's familiarity ([Angular Templates](https://angular.io/guide/template-syntax)).  Ubiquity, even ([JSX Spec](https://facebook.github.io/jsx/)).

I spent some time recently experimenting with [RethinkDB](https://rethinkdb.com/).  Its own [query language](https://rethinkdb.com/docs/introduction-to-reql/) looks nothing like escuelle.  Now, I'm no stranger to transforming data sets through functional pipelines.  Be it in Java, or Spark, or Clojure, or even bash, this is a common paradigm for me to work with.  Still, ReQL comes with a [learning curve](https://rethinkdb.com/docs/table-joins/).  Full disclosure, I found the [cheat sheet](https://rethinkdb.com/docs/sql-to-reql/javascript/) extremely helpful.

By their own admission, RethinkDB lacks a [query optimizer](https://rethinkdb.com/docs/introduction-to-reql/#query-optimization).  At the time, I saw this as a limitation.  I don't anymore.  Why?  Because one day I switched back to escuelle, and I found that I was no longer in the driver's seat.

I was cobbling together something much larger, but here's my simple starting point.

```sql
select d.*
from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
  left join drivetrain d on d.eventId = t.eventId
;

(2 rows)

Time: 2.269 ms
```

Only one of those two rows was the one I wanted, so I added a filter to limit the output.  The performance *plummets*.

```sql
select d.*
from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
  left join drivetrain d on d.eventId = t.eventId
where d.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;

(1 row)

Time: 1650.500 ms (00:01.650)
```

Okay, PostgreSQL, explain yourself.  The first query ...

```sql
explain select d.*
from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
  left join drivetrain d on d.eventId = t.eventId
;
                                         QUERY PLAN                                          
---------------------------------------------------------------------------------------------
 Nested Loop Left Join  (cost=0.68..8076.75 rows=1000 width=1086)
   ->  Function Scan on event_trace_up t  (cost=0.25..10.25 rows=1000 width=16)
   ->  Index Scan using drivetrain_pkey on drivetrain d  (cost=0.42..8.07 rows=1 width=1086)
         Index Cond: (eventid = t.eventid)
```

... vs. the second.

```sql
explain select d.*
from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
  left join drivetrain d on d.eventId = t.eventId
where d.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;
                                              QUERY PLAN                                              
------------------------------------------------------------------------------------------------------
 Hash Join  (cost=3897.90..3920.53 rows=1 width=1086)
   Hash Cond: (t.eventid = d.eventid)
   ->  Function Scan on event_trace_up t  (cost=0.25..10.25 rows=1000 width=16)
   ->  Hash  (cost=3885.06..3885.06 rows=1007 width=1086)
         ->  Bitmap Heap Scan on drivetrain d  (cost=107.81..3885.06 rows=1007 width=1086)
               Recheck Cond: (message @> '{"info": {"triggeredBy": {"@type": "fs"}}}'::jsonb)
               ->  Bitmap Index Scan on drivetrainmessageindex  (cost=0.00..107.56 rows=1007 width=0)
                     Index Cond: (message @> '{"info": {"triggeredBy": {"@type": "fs"}}}'::jsonb)
```

Obviously the function is fast, and the index scan against the primary key is also fast.  Scanning the jsonb index turns out to be several orders of magnitude slower, yet the planner puts that front and center in the plan for the second query.  Now I have to fiddle with the syntax until I can outsmart the planner.

A subquery does not regain performance ...

```sql
select q.*
from (
  select d.*
  from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
    left join drivetrain d on d.eventId = t.eventId
) q
where q.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;

(1 row)

Time: 1657.502 ms (00:01.658)
```

... because the plan does not change.

```sql
explain select q.*
from (
  select d.*
  from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
    left join drivetrain d on d.eventId = t.eventId
) q
where q.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;
                                              QUERY PLAN                                              
------------------------------------------------------------------------------------------------------
 Hash Join  (cost=3897.90..3920.53 rows=1 width=1086)
   Hash Cond: (t.eventid = d.eventid)
   ->  Function Scan on event_trace_up t  (cost=0.25..10.25 rows=1000 width=16)
   ->  Hash  (cost=3885.06..3885.06 rows=1007 width=1086)
         ->  Bitmap Heap Scan on drivetrain d  (cost=107.81..3885.06 rows=1007 width=1086)
               Recheck Cond: (message @> '{"info": {"triggeredBy": {"@type": "fs"}}}'::jsonb)
               ->  Bitmap Index Scan on drivetrainmessageindex  (cost=0.00..107.56 rows=1007 width=0)
                     Index Cond: (message @> '{"info": {"triggeredBy": {"@type": "fs"}}}'::jsonb)
```

Thankfully, a common table expression does the trick:

```sql
with q as (
  select d.*
  from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
    left join drivetrain d on d.eventId = t.eventId
)
select *
from q
where q.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;

(1 row)

Time: 2.357 ms
```

And at last here's the plan I was hoping for all along.

```sql
explain with q as (
  select d.*
  from event_trace_up('783eba47-9bf3-4fc6-898f-9b01cfe5bf64') t
    left join drivetrain d on d.eventId = t.eventId
)
select *
from q
where q.message @> '{"info":{"triggeredBy":{"@type":"fs"}}}'
;
                                             QUERY PLAN                                              
-----------------------------------------------------------------------------------------------------
 CTE Scan on q  (cost=8076.75..8099.25 rows=1 width=68)
   Filter: (message @> '{"info": {"triggeredBy": {"@type": "fs"}}}'::jsonb)
   CTE q
     ->  Nested Loop Left Join  (cost=0.68..8076.75 rows=1000 width=1086)
           ->  Function Scan on event_trace_up t  (cost=0.25..10.25 rows=1000 width=16)
           ->  Index Scan using drivetrain_pkey on drivetrain d  (cost=0.42..8.07 rows=1 width=1086)
                 Index Cond: (eventid = t.eventid)
```

Mind you, there was nothing semantically wrong with the first query.  Or the second.  And therein lies the problem.  Escuelle is promoted as a [high-level language](https://www.fossil-scm.org/index.html/doc/trunk/www/theory1.wiki) that enables you to solve your problems declaratively.  Like so many things ([Spring Boot](https://projects.spring.io/spring-boot/)), it's great when it works, and it's unnerving when it doesn't.
