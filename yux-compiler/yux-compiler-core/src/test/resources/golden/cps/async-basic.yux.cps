module {
 class Async-basic (file) {
  static synthetic method $clinit(): Unit {
  }
  static async method fetch(n: Int): Task {
   LocalAssign #5: CompletableFuture = Invoke yux.async.Continuations.newFuture []
   LocalAssign #6: Async-basic$fetch$Sm = New Async-basic$fetch$Sm [Const(null), LocalRead(#0), New FutureCompletion [LocalRead(#5)]]
   LocalAssign #1: Async-basic$fetch$Sm = LocalRead(#6)
   LocalAssign #2: CompletableFuture = LocalRead(#5)
   Try
    LocalAssign #3: Any = Invoke Async-basic$fetch$Sm#invokeSuspend [LocalRead(#1)]
    Branch Compare(EQ, LocalRead(#3), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call java.util.concurrent.CompletableFuture#complete [LocalRead(#2), LocalRead(#3)] : Boolean
    Label L0
   Catch facade$e
    Call java.util.concurrent.CompletableFuture#completeExceptionally [LocalRead(#2), LocalRead(#4)] : Boolean
   EndTry
   Return Invoke yux.async.Continuations.wrap [LocalRead(#5)]
  }
  static async synthetic method fetch$suspend(n: Int, continuation: Continuation): Any {
   LocalAssign #3: Async-basic$fetch$Sm = New Async-basic$fetch$Sm [Const(null), LocalRead(#0), LocalRead(#1)]
   Return Invoke Async-basic$fetch$Sm#invokeSuspend [LocalRead(#3)]
  }
  static async method guarded(): Task {
   LocalAssign #4: CompletableFuture = Invoke yux.async.Continuations.newFuture []
   LocalAssign #5: Async-basic$guarded$Sm = New Async-basic$guarded$Sm [Const(null), New FutureCompletion [LocalRead(#4)]]
   LocalAssign #0: Async-basic$guarded$Sm = LocalRead(#5)
   LocalAssign #1: CompletableFuture = LocalRead(#4)
   Try
    LocalAssign #2: Any = Invoke Async-basic$guarded$Sm#invokeSuspend [LocalRead(#0)]
    Branch Compare(EQ, LocalRead(#2), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call java.util.concurrent.CompletableFuture#complete [LocalRead(#1), LocalRead(#2)] : Boolean
    Label L0
   Catch facade$e
    Call java.util.concurrent.CompletableFuture#completeExceptionally [LocalRead(#1), LocalRead(#3)] : Boolean
   EndTry
   Return Invoke yux.async.Continuations.wrap [LocalRead(#4)]
  }
  static async synthetic method guarded$suspend(continuation: Continuation): Any {
   LocalAssign #2: Async-basic$guarded$Sm = New Async-basic$guarded$Sm [Const(null), LocalRead(#0)]
   Return Invoke Async-basic$guarded$Sm#invokeSuspend [LocalRead(#2)]
  }
  static async method loop(n: Int): Task {
   LocalAssign #5: CompletableFuture = Invoke yux.async.Continuations.newFuture []
   LocalAssign #6: Async-basic$loop$Sm = New Async-basic$loop$Sm [Const(null), LocalRead(#0), New FutureCompletion [LocalRead(#5)]]
   LocalAssign #1: Async-basic$loop$Sm = LocalRead(#6)
   LocalAssign #2: CompletableFuture = LocalRead(#5)
   Try
    LocalAssign #3: Any = Invoke Async-basic$loop$Sm#invokeSuspend [LocalRead(#1)]
    Branch Compare(EQ, LocalRead(#3), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call java.util.concurrent.CompletableFuture#complete [LocalRead(#2), LocalRead(#3)] : Boolean
    Label L0
   Catch facade$e
    Call java.util.concurrent.CompletableFuture#completeExceptionally [LocalRead(#2), LocalRead(#4)] : Boolean
   EndTry
   Return Invoke yux.async.Continuations.wrap [LocalRead(#5)]
  }
  static async synthetic method loop$suspend(n: Int, continuation: Continuation): Any {
   LocalAssign #3: Async-basic$loop$Sm = New Async-basic$loop$Sm [Const(null), LocalRead(#0), LocalRead(#1)]
   Return Invoke Async-basic$loop$Sm#invokeSuspend [LocalRead(#3)]
  }
  static synthetic method lambda$0(): Any {
   Return Const(null)
  }
  static synthetic method lambda$1(): Any {
   Throw New RuntimeException []
  }
  static synthetic method lambda$2(): Any {
   Return Const(null)
  }
 }
 class Async-basic$fetch$Sm {
  field state: Int
  field receiver: Any
  field completion: Continuation
  field result: Any
  field exception: Any
  field rethrown: Any
  field returnValue: Any
  field l0$n: Int
  field l1$t: Task?
  field l2$await$result: Any
  field l3$await$result: Int
  synthetic method invokeSuspend(): Any {
   Branch Compare(EQ, FieldRead(this.state), Const(0)) state0 L0
   Label state0
   FieldWrite this.l1$t = Invoke yux.async.Tasks.launch [Lambda lambda$0]
   FieldWrite this.state = Const(1)
   LocalAssign #2: Any = Invoke yux.async.Continuations.tryAwait [FieldRead(this.l1$t), This]
   Branch Compare(EQ, LocalRead(#2), FieldRead(SUSPENDED)) L7 L6
   Label L7
   Return FieldRead(SUSPENDED)
   Label L6
   Branch IsType(LocalRead(#2), PendingException) L9 L8
   Label L9
   FieldWrite this.exception = FieldRead((Convert(LocalRead(#2), PendingException)).throwable)
   Goto state1
   Label L8
   FieldWrite this.result = LocalRead(#2)
   Label state1
   Branch Compare(EQ, FieldRead(this.exception), Const(null)) L10 L11
   Label L11
   Throw Convert(FieldRead(this.exception), Throwable)
   Label L10
   FieldWrite this.l2$await$result = FieldRead(this.result)
   Branch Compare(GT, FieldRead(this.l0$n), Const(0)) state2 state4
   Label state2
   Label L0
   FieldWrite this.state = Const(3)
   LocalAssign #3: Any = Invoke Async-basic.fetch$suspend [Arith(SUB, FieldRead(this.l0$n), Const(1)), This]
   Branch Compare(EQ, LocalRead(#3), FieldRead(SUSPENDED)) L13 L12
   Label L13
   Return FieldRead(SUSPENDED)
   Label L12
   FieldWrite this.result = LocalRead(#3)
   Label state3
   Branch Compare(EQ, FieldRead(this.exception), Const(null)) L14 L15
   Label L15
   Throw Convert(FieldRead(this.exception), Throwable)
   Label L14
   FieldWrite this.l3$await$result = Convert(FieldRead(this.result), Int)
   Return FieldRead(this.l3$await$result)
   Label state4
   Label L1
   Return Const(0)
  }
  override synthetic method resume(value: Any): Unit {
   FieldWrite this.result = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$fetch$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  override synthetic method resumeWithException(t: Throwable): Unit {
   FieldWrite this.exception = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$fetch$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  synthetic method <init>(receiver: Any, n: Int, completion: Continuation): Unit {
   FieldWrite this.state = Const(0)
   FieldWrite this.receiver = LocalRead(#0)
   FieldWrite this.l0$n = LocalRead(#1)
   FieldWrite this.completion = LocalRead(#2)
  }
 }
 class Async-basic$guarded$Sm {
  field state: Int
  field receiver: Any
  field completion: Continuation
  field result: Any
  field exception: Any
  field rethrown: Any
  field returnValue: Any
  field l0$t: Task?
  field l1$await$result: Any
  synthetic method invokeSuspend(): Any {
   Branch Compare(EQ, FieldRead(this.state), Const(0)) state0 L4
   Label L4
   Branch Compare(EQ, FieldRead(this.state), Const(1)) state1 L5
   Label L5
   Branch Compare(EQ, FieldRead(this.state), Const(2)) state2 L6
   Label L6
   Branch Compare(EQ, FieldRead(this.state), Const(3)) state3 L7
   Label L7
   Branch Compare(EQ, FieldRead(this.state), Const(4)) state4 L8
   Label L8
   Branch Compare(EQ, FieldRead(this.state), Const(5)) state5 L9
   Label L9
   Branch Compare(EQ, FieldRead(this.state), Const(6)) state6 L10
   Label L10
   Branch Compare(EQ, FieldRead(this.state), Const(7)) state7 L11
   Label L11
   Branch Compare(EQ, FieldRead(this.state), Const(8)) state8 L12
   Label L12
   Return Const(null)
   Label state0
   Goto state3
   Label state1
   LocalAssign #0: Throwable = Convert(FieldRead(this.exception), Throwable)
   Branch IsType(LocalRead(#0), CancellationException) L0 L1
   Label L1
   Label L2
   FieldWrite this.exception = Const(null)
   Goto state5
   Label L3
   Label L0
   FieldWrite this.rethrown = LocalRead(#0)
   Goto state7
   Label state2
   Call yux.core.CoreLib.print [Const("fin")] : Unit
   Label state3
   Try
    FieldWrite this.l0$t = Invoke yux.async.Tasks.launch [Lambda lambda$1]
    FieldWrite this.state = Const(4)
    LocalAssign #1: Any = Invoke yux.async.Continuations.tryAwait [FieldRead(this.l0$t), This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L14 L13
    Label L14
    Return FieldRead(SUSPENDED)
    Label L13
    Branch IsType(LocalRead(#1), PendingException) L16 L15
    Label L16
    FieldWrite this.exception = FieldRead((Convert(LocalRead(#1), PendingException)).throwable)
    Goto state4
    Label L15
    FieldWrite this.result = LocalRead(#1)
    Goto state4
    Nop
   Catch exc$routing
    FieldWrite this.exception = LocalRead(#2)
    Goto state1
   EndTry
   Label state4
   Try
    Branch Compare(EQ, FieldRead(this.exception), Const(null)) L17 L18
    Label L18
    Goto state1
    Label L17
    FieldWrite this.l1$await$result = FieldRead(this.result)
    Goto state9
   Catch exc$routing
    FieldWrite this.exception = LocalRead(#3)
    Goto state1
   EndTry
   Label state5
   Return Const(42)
   Label state6
   Throw Convert(FieldRead(this.rethrown), Throwable)
   Label state7
   Call yux.core.CoreLib.print [Const("fin")] : Unit
   Goto state6
   Label state8
   Return FieldRead(this.returnValue)
   Label state9
   Try
    FieldWrite this.returnValue = Convert(Const(1), Any)
    Goto state10
   Catch exc$routing
    FieldWrite this.exception = LocalRead(#4)
    Goto state1
   EndTry
   Label state10
   Call yux.core.CoreLib.print [Const("fin")] : Unit
   Goto state8
  }
  override synthetic method resume(value: Any): Unit {
   FieldWrite this.result = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$guarded$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  override synthetic method resumeWithException(t: Throwable): Unit {
   FieldWrite this.exception = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$guarded$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  synthetic method <init>(receiver: Any, completion: Continuation): Unit {
   FieldWrite this.state = Const(0)
   FieldWrite this.receiver = LocalRead(#0)
   FieldWrite this.completion = LocalRead(#1)
  }
 }
 class Async-basic$loop$Sm {
  field state: Int
  field receiver: Any
  field completion: Continuation
  field result: Any
  field exception: Any
  field rethrown: Any
  field returnValue: Any
  field l0$n: Int
  field l1$total: Int
  field l2$i: Int
  field l3$t: Task?
  field l4$await$result: Any
  synthetic method invokeSuspend(): Any {
   Branch Compare(EQ, FieldRead(this.state), Const(0)) state0 L0
   Label state0
   FieldWrite this.l1$total = Const(0)
   FieldWrite this.l2$i = Const(0)
   Label state1
   Label L0
   Branch Compare(LT, FieldRead(this.l2$i), FieldRead(this.l0$n)) state2 state4
   Label state2
   Label L1
   FieldWrite this.l3$t = Invoke yux.async.Tasks.launch [Lambda lambda$2]
   FieldWrite this.state = Const(3)
   LocalAssign #1: Any = Invoke yux.async.Continuations.tryAwait [FieldRead(this.l3$t), This]
   Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L7 L6
   Label L7
   Return FieldRead(SUSPENDED)
   Label L6
   Branch IsType(LocalRead(#1), PendingException) L9 L8
   Label L9
   FieldWrite this.exception = FieldRead((Convert(LocalRead(#1), PendingException)).throwable)
   Goto state3
   Label L8
   FieldWrite this.result = LocalRead(#1)
   Label state3
   Branch Compare(EQ, FieldRead(this.exception), Const(null)) L10 L11
   Label L11
   Throw Convert(FieldRead(this.exception), Throwable)
   Label L10
   FieldWrite this.l4$await$result = FieldRead(this.result)
   FieldWrite this.l1$total = Arith(ADD, FieldRead(this.l1$total), FieldRead(this.l2$i))
   FieldWrite this.l2$i = Arith(ADD, FieldRead(this.l2$i), Const(1))
   Goto state1
   Label state4
   Label L2
   Return FieldRead(this.l1$total)
  }
  override synthetic method resume(value: Any): Unit {
   FieldWrite this.result = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$loop$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  override synthetic method resumeWithException(t: Throwable): Unit {
   FieldWrite this.exception = LocalRead(#0)
   Try
    LocalAssign #1: Any = Invoke Async-basic$loop$Sm#invokeSuspend [This]
    Branch Compare(EQ, LocalRead(#1), FieldRead(SUSPENDED)) L0 L1
    Label L1
    Call yux.async.Continuation#resume [FieldRead(this.completion), LocalRead(#1)] : Unit
    Label L0
   Catch drive$e
    Call yux.async.Continuation#resumeWithException [FieldRead(this.completion), LocalRead(#2)] : Unit
   EndTry
  }
  synthetic method <init>(receiver: Any, n: Int, completion: Continuation): Unit {
   FieldWrite this.state = Const(0)
   FieldWrite this.receiver = LocalRead(#0)
   FieldWrite this.l0$n = LocalRead(#1)
   FieldWrite this.completion = LocalRead(#2)
  }
 }
}
