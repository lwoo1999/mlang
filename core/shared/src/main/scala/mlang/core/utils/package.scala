package mlang.core

package object utils {

  implicit class Text(val s: String) extends AnyVal {
    def string: String = s
    override def toString: String = s.toString
  }
}
