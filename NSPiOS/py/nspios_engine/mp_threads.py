"""multiprocessing mit Threads: iOS startet keine Zusatzprozesse und hat kein _multiprocessing.

install() ersetzt get_context(), die Funktionen auf Modulebene (Pool, Lock, Queue, Process, Manager, Value …) und
multiprocessing.pool.Pool. Alles läuft im selben Prozess; das, was der Packer davon nutzt (Pools mit Initializer,
Sperren, Zähler, Warteschlangen), verhält sich dabei gleich.
"""
import os, queue, threading, itertools

_ids = itertools.count(1)


class Process(threading.Thread):
    def __init__(self, group=None, target=None, name=None, args=(), kwargs=None, *, daemon=None):
        super().__init__(target=target, name=name, args=args, kwargs=kwargs or {}, daemon=daemon)
        self.exitcode = None
        self._ident = next(_ids)

    def run(self):
        try:
            super().run()
            self.exitcode = 0
        except SystemExit as e:
            self.exitcode = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
        except BaseException:
            import traceback
            traceback.print_exc()
            self.exitcode = 1

    @property
    def pid(self):
        return os.getpid()

    @property
    def sentinel(self):
        return self._ident

    def terminate(self):        # Threads lassen sich nicht abbrechen: wie ein Prozess, der das Signal ignoriert
        pass

    kill = terminate

    def close(self):
        pass


class Queue(queue.Queue):
    def __init__(self, maxsize=0, *, ctx=None):
        super().__init__(maxsize if maxsize and maxsize > 0 else 0)

    def close(self):
        pass

    def join_thread(self):
        pass

    def cancel_join_thread(self):
        pass


class JoinableQueue(Queue):
    pass


class SimpleQueue(queue.SimpleQueue):
    def close(self):
        pass


class _Synchronized:
    """Value/Array mit get_lock(), wie sharedctypes.Synchronized."""
    def __init__(self, value, lock=True):
        self._value = value
        self._lock = lock if hasattr(lock, 'acquire') else threading.RLock()

    def get_lock(self):
        return self._lock

    def get_obj(self):
        return self._value

    def acquire(self, *a):
        return self._lock.acquire(*a)

    def release(self):
        return self._lock.release()

    def __enter__(self):
        return self._lock.__enter__()

    def __exit__(self, *a):
        return self._lock.__exit__(*a)


class Value(_Synchronized):
    def __init__(self, typecode_or_type=None, *args, lock=True):
        super().__init__(args[0] if args else 0, lock)

    @property
    def value(self):
        return self._value

    @value.setter
    def value(self, v):
        self._value = v


class Array(_Synchronized):
    def __init__(self, typecode_or_type=None, size_or_initializer=0, *, lock=True):
        init = [0] * size_or_initializer if isinstance(size_or_initializer, int) else list(size_or_initializer)
        super().__init__(init, lock)

    def __len__(self):
        return len(self._value)

    def __getitem__(self, i):
        return self._value[i]

    def __setitem__(self, i, v):
        self._value[i] = v

    def __iter__(self):
        return iter(self._value)

    def __getattr__(self, name):        # .value / .raw bei char-Arrays
        if name in ('value', 'raw'):
            v = self._value
            return bytes(v) if v and isinstance(v[0], int) else v
        raise AttributeError(name)


class _Connection:
    def __init__(self, inbox, outbox):
        self._in, self._out, self.closed = inbox, outbox, False

    def send(self, obj):
        self._out.put(obj)

    def recv(self):
        obj = self._in.get()
        if obj is _CLOSED:
            self._in.put(_CLOSED)
            raise EOFError
        return obj

    send_bytes, recv_bytes = send, recv

    def poll(self, timeout=0.0):
        return not self._in.empty()

    def close(self):
        if not self.closed:
            self.closed = True
            self._out.put(_CLOSED)

    def fileno(self):
        raise OSError('no file descriptor (thread pipe)')


_CLOSED = object()


def Pipe(duplex=True):
    a, b = queue.Queue(), queue.Queue()
    return _Connection(a, b), _Connection(b, a)


class _Namespace:
    def __repr__(self):
        return 'Namespace(%s)' % ', '.join('%s=%r' % kv for kv in vars(self).items())


class Manager:
    """SyncManager-Ersatz: gibt gewöhnliche, threadsichere Objekte desselben Prozesses aus."""
    def __init__(self, *a, **k):
        pass

    def start(self, *a, **k):
        return self

    def shutdown(self):
        pass

    def connect(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.shutdown()

    dict = staticmethod(lambda *a, **k: dict(*a, **k))
    list = staticmethod(lambda *a: list(*a))
    Namespace = staticmethod(lambda **k: _ns(k))
    Lock = staticmethod(threading.Lock)
    RLock = staticmethod(threading.RLock)
    Event = staticmethod(threading.Event)
    Condition = staticmethod(threading.Condition)
    Semaphore = staticmethod(threading.Semaphore)
    BoundedSemaphore = staticmethod(threading.BoundedSemaphore)
    Barrier = staticmethod(threading.Barrier)
    Queue = staticmethod(lambda maxsize=0: Queue(maxsize))
    JoinableQueue = staticmethod(lambda maxsize=0: JoinableQueue(maxsize))
    Value = staticmethod(lambda t, v, lock=True: Value(t, v, lock=lock))
    Array = staticmethod(lambda t, s, lock=True: Array(t, s, lock=lock))


def _ns(k):
    n = _Namespace()
    vars(n).update(k)
    return n


class ThreadContext:
    """Was get_context() liefert – egal ob 'fork', 'spawn' oder 'forkserver' verlangt wird."""
    _name = 'fork'

    def __init__(self, pool_cls):
        self._pool = pool_cls

    def get_start_method(self, allow_none=False):
        return self._name

    def set_start_method(self, method, force=False):
        pass

    def get_all_start_methods(self):
        return ['fork', 'spawn', 'forkserver']

    def get_context(self, method=None):
        return self

    def cpu_count(self):
        return os.cpu_count() or 1

    def current_process(self):
        return threading.current_thread()

    def parent_process(self):
        return None

    def active_children(self):
        return []

    def freeze_support(self):
        pass

    def set_executable(self, path):
        pass

    def set_forkserver_preload(self, modules):
        pass

    def log_to_stderr(self, level=None):
        import logging
        return logging.getLogger('multiprocessing')

    def get_logger(self):
        import logging
        return logging.getLogger('multiprocessing')

    def allow_connection_pickling(self):
        pass

    def Pool(self, processes=None, initializer=None, initargs=(), maxtasksperchild=None):
        return self._pool(processes, initializer, initargs, maxtasksperchild)

    Process = Process
    Queue = staticmethod(lambda maxsize=0: Queue(maxsize))
    JoinableQueue = staticmethod(lambda maxsize=0: JoinableQueue(maxsize))
    SimpleQueue = staticmethod(lambda: SimpleQueue())
    Pipe = staticmethod(Pipe)
    Lock = staticmethod(threading.Lock)
    RLock = staticmethod(threading.RLock)
    Event = staticmethod(threading.Event)
    Condition = staticmethod(threading.Condition)
    Semaphore = staticmethod(threading.Semaphore)
    BoundedSemaphore = staticmethod(threading.BoundedSemaphore)
    Barrier = staticmethod(threading.Barrier)
    Value = staticmethod(lambda t, *a, lock=True: Value(t, *a, lock=lock))
    Array = staticmethod(lambda t, s, *, lock=True: Array(t, s, lock=lock))
    RawValue = staticmethod(lambda t, *a: Value(t, *a))
    RawArray = staticmethod(lambda t, s: Array(t, s))
    Manager = staticmethod(lambda *a, **k: Manager())


_NAMES = ('Process', 'Queue', 'JoinableQueue', 'SimpleQueue', 'Pipe', 'Lock', 'RLock', 'Event', 'Condition',
          'Semaphore', 'BoundedSemaphore', 'Barrier', 'Value', 'Array', 'RawValue', 'RawArray', 'Manager', 'Pool',
          'cpu_count', 'current_process', 'parent_process', 'active_children', 'freeze_support', 'set_executable',
          'set_start_method', 'get_start_method', 'get_all_start_methods', 'set_forkserver_preload', 'get_logger',
          'log_to_stderr', 'allow_connection_pickling')


def install(pool_cls):
    import multiprocessing, multiprocessing.context as context
    ctx = ThreadContext(pool_cls)
    for name in _NAMES:
        setattr(multiprocessing, name, getattr(ctx, name))
    multiprocessing.get_context = ctx.get_context
    context._default_context = ctx
    for cls in [context.BaseContext] + [c for c in vars(context).values()
                                         if isinstance(c, type) and issubclass(c, context.BaseContext)]:
        cls.Pool = lambda self, *a, **k: pool_cls(*a, **k)
    try:
        import multiprocessing.pool as mpool
        mpool.Pool = lambda *a, **k: pool_cls(*a, **k)
        mpool.ThreadPool = lambda *a, **k: pool_cls(*a, **k)
    except ImportError:
        pass
    try:
        import multiprocessing.dummy as dummy
        dummy.Pool = lambda processes=None, initializer=None, initargs=(): pool_cls(processes, initializer, initargs)
    except ImportError:
        pass
    return ctx
