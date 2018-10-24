from lldb import SBValue, SBType


###################################################
# You can find some useful information here:
#   1. https://lldb.llvm.org/varformats.html
#   2. https://lldb.llvm.org/python-reference.html
###################################################


def SizeSummaryProvider(valobj, internal_dict):
    # type: (SBValue, dict) -> str
    return 'size=' + str(valobj.GetNumChildren())


################################################################################################################
# Synthetic children is the way to provide a children-based user-friendly representation of the object's value.
# To create synthetic children, you need to provide a class that implements a given interface:
#
#     class SyntheticChildrenProvider:
#         def __init__(self, valobj: SBValue, internal_dict: dict)
#         def num_children(self)
#         def get_child_index(self, name: str)
#         def get_child_at_index(self, index: int)
#         def update(self)
#         def has_children(self)
#         def get_value(self)
#
# `internal_dict` is an internal support parameter used by LLDB and you should not touch it;
# `valobj` is the object encapsulating the actual variable being displayed;
################################################################################################################

class StdVecProvider:
    """alloc::vec::Vec<T>

    struct Vec<T> { buf: RawVec<T>, len: usize }
    struct RawVec<T> { ptr: Unique<T>, cap: usize, ... }
    struct Unique<T: ?Sized> { pointer: NonZero<*const T>, ... }
    struct NonZero<T>(T)
    """
    def __init__(self, valobj, internal_dict):
        # type: (SBValue, dict) -> StdVecProvider
        self.valobj = valobj

    def num_children(self):
        # type: () -> int
        return self.length

    def get_child_index(self, name):
        # type: (str) -> int
        index = name.lstrip('[').rstrip(']')
        if index.isdigit():
            return int(index)
        else:
            return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        start = self.data_ptr.GetValueAsUnsigned()
        address = start + index * self.element_type_size
        element = self.data_ptr.CreateValueFromAddress("[%s]" % index, address, self.element_type)
        return element

    def update(self):
        # type: () -> None
        self.length = self.valobj.GetChildMemberWithName("len").GetValueAsUnsigned()  # type: int
        self.buf = self.valobj.GetChildMemberWithName("buf")  # type: SBValue
        self.data_ptr = self.buf.GetChildAtIndex(0).GetChildAtIndex(0).GetChildAtIndex(0)  # type: SBValue
        self.element_type = self.data_ptr.GetType().GetPointeeType()  # type: SBType
        self.element_type_size = self.element_type.GetByteSize()  # type: int

    def has_children(self):
        # type: () -> bool
        return True
