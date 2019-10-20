
use self :: y :: { self   };
use           :: { self   };
use           :: { self , };
use           :: {        };
use              { y      };
use              { y ,    };
use              {        };
use self :: y :: *;
use self :: y as z;
use self :: y as _;
use self :: y;
use crate :: y;

// https://github.com/rust-lang/rfcs/blob/master/text/2128-use-nested-groups.md
use a::{B, d::{self, *, g::H}};
use ::{*, *};

use foo::{bar, {baz, quux}};
use {crate::foo, crate::bar, super::baz};
