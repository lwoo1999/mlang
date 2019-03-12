
# mlang

## build & run & test

standard SBT project, run `Main` with file name to check, test with standard SBT commands

## code

* packages and files is indexed by "a-z", resulting in a linear order of files. you 
should be able to read them one by one

## mvp

---

(in progress)

* **pi**: function types with eta rule, mutually recursively defined lambdas with size-changing termination checker
* **path**: cubical type theory (`TODO: eta for path?`)
* **universe**: culmutative universe with subtyping with univalance
* **inductive**: uniform-parametized simple higher inductive type (no index, no non-uniform, no mutual, no induction-recursion, no induction-induction)
* **record**: record type with record type calculus, make expressions
* case lambda
* coercion subtyping

## maybe roadmap

* pattern matching and coverage checker
* overlapping and definitional patterns
* all other forms of inductive type mentioned above, except induction-induction
* implicit parameters and possibly more liberal unification
* ad-hoc polymorphism
* explicit universe polymorphism with editor helper


## math

* quick sort and properties
* symmetry book
* cubical Agda
* Agda stdlib
* Artin's or Lang's *Algebra*
* Agda's test cases and it's issues
* https://ncatlab.org/homotopytypetheory/show/open+problems#higher_algebra_and_higher_category_theory
    * seems interesting: limits problem?
* https://github.com/HoTT/HoTT
* unimath
* the big problems list


## misc

* translate to Agda to do correctness checking