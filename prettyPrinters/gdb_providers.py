from sys import version_info

if version_info[0] >= 3:
    xrange = range


class StructProvider:
    def __init__(self, valobj, is_variant=False):
        self.valobj = valobj
        self.is_variant = is_variant
        fields = self.valobj.type.fields()
        self.fields = fields[1:] if is_variant else fields

    def children(self):
        for i, field in enumerate(self.fields):
            yield ((field.name, self.valobj[field.name]))


class TupleProvider:
    def __init__(self, valobj, is_variant=False):
        self.valobj = valobj
        self.is_variant = is_variant
        fields = self.valobj.type.fields()
        self.fields = fields[1:] if is_variant else fields

    def children(self):
        for i, field in enumerate(self.fields):
            yield ((str(i), self.valobj[field.name]))

    @staticmethod
    def display_hint():
        return "array"


class StdStringProvider:
    def __init__(self, valobj):
        self.valobj = valobj
        vec = valobj["vec"]
        self.length = int(vec["len"])
        self.data_ptr = vec["buf"]["ptr"]["pointer"]["__0"]

    def to_string(self):
        return self.data_ptr.lazy_string(encoding="utf-8", length=self.length)

    @staticmethod
    def display_hint():
        return "string"


class StdStrProvider:
    def __init__(self, valobj):
        self.valobj = valobj
        self.length = int(valobj["length"])
        self.data_ptr = valobj["data_ptr"]

    def to_string(self):
        return self.data_ptr.lazy_string(encoding="utf-8", length=self.length)

    @staticmethod
    def display_hint():
        return "string"


class StdVecProvider:
    def __init__(self, valobj):
        self.valobj = valobj
        self.length = int(valobj["len"])
        self.data_ptr = valobj["buf"]["ptr"]["pointer"]["__0"]

    def to_string(self):
        return "len=" + str(self.length)

    def children(self):
        for index in xrange(0, self.length):
            yield (str(index), (self.data_ptr + index).dereference())

    @staticmethod
    def display_hint():
        return "array"


class StdVecDequeProvider:
    def __init__(self, valobj):
        self.valobj = valobj
        self.head = int(valobj["head"])
        self.tail = int(valobj["tail"])
        self.buf = valobj["buf"]
        self.cap = int(self.buf["cap"])
        self.size = self.head - self.tail if self.head >= self.tail else self.cap + self.head - self.tail
        self.data_ptr = self.buf["ptr"]["pointer"]["__0"]

    def to_string(self):
        return "len=" + str(self.size)

    def children(self):
        for index in xrange(0, self.size):
            yield (str(index), (self.data_ptr + ((self.tail + index) % self.cap)).dereference())

    @staticmethod
    def display_hint():
        return "array"
