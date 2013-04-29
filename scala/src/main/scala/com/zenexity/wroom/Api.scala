package com.zenexity.wroom.client

import scala.concurrent._
import org.joda.time._

import play.api.libs.json._
import play.api.libs.functional.syntax._

trait Api {
  def data: ApiData
  def baseUrl: String

  def refs: Map[String, Ref] = data.refs.groupBy(_.label).mapValues(_.head)
  def bookmarks: Map[String, String] = data.bookmarks
  def forms: Map[String, Form] = data.forms

  def master: RefApi = data.refs.collectFirst{ case ref if ref.isMasterRef => ref } match {
    case Some(r) => RefApi(this, r)
    case None    => sys.error("potential problem: no master reference found")
  }

  def ref(refId: String): RefApi = RefApi(this, refs(refId))
  def getRef(refId: String): Option[RefApi] = refs.get(refId) map { r => RefApi(this, r) }

  def bookmark(name: String): String = bookmarks(name)

  override def toString = s"""Api:
    -> data: ${Json.prettyPrint(Json.toJson(data))}
  """
}

case class RefApi(api: Api, ref: Ref) {
  def form(s: String): FormApi = FormApi(this, api.forms(s))
  def getForm(s: String): Option[FormApi] = api.forms.get(s) map { f => FormApi(this, f) }

  def documents = form("documents")
  def document(id: String) = form("document").withFields("id" -> id).submit()
}

case class FormApi(
  refapi: RefApi, 
  form: Form, 
  fields: Seq[Field] = Seq(), 
  widgets: Seq[Widget] = Seq()
) {
  def withFields(theFields: Field*): FormApi = this.copy( fields = fields ++ theFields )
  def withFields(field: (String, Any), theFields: (String, Any)*): FormApi = withFields((field +: theFields).map{ case(k,v) => Field(k,v) }:_*)

  def withWidgets(newWidgets: Widget*): FormApi = this.copy( widgets = widgets ++ newWidgets )
  def withWidgets(widget: (String, Render), newWidgets: (String, Render)*): FormApi = withWidgets((widget +: newWidgets).map{ case (name, render) => Widget(name, render) }: _*)
  def withWidgets(widget: String, newWidgets: String*): FormApi = withWidgets((widget +: newWidgets).map{ name => Widget(name) }: _*)

  private def urlEncodeField(typ: String, multiple: Boolean, value: Any): Seq[String] = {
    typ match {
      case "String" => multiple match {
        case false  => Seq(java.net.URLEncoder.encode(value.toString))
        case true => value.asInstanceOf[Seq[Any]].map(e => java.net.URLEncoder.encode(e.toString))
      }
      case _ => sys.error("unknown type")
    }
  }

  private def queryString: Map[String, Seq[String]] = {
    (Map("ref" -> Seq(java.net.URLEncoder.encode(refapi.ref.ref))) ++ 
    fields.map{ case Field(k,v) => 
      // converting field according to form fields
      form.fields.get(k) match {
        case Some(field) => k -> urlEncodeField(field.typ, field.multiple, v)
        case None        => sys.error("Form doesn't accept this field")
      }
      
    }) + ("widgets" -> widgets.map{ w => w.toString })

    // TODO add default values
    
  }
  
  def submit(): Future[Response] = {
    val method = form.method
    val enctype = form.enctype
    var holder = WS.url(form.action)
    method match {
      case "GET" => 
        enctype match {
          case "application/x-www-form-urlencoded" => 
            holder = holder.copy(queryString = queryString)
          case _ => sys.error("enctype not managed")
        }
        // JSON for now
        holder = holder.withHeaders("Accept" -> "application/json")
        println("holder:"+holder)
        holder.get()
    }
  }

}

sealed trait Render
object Render {
  case object HTML extends Render {
    override def toString = "html"
  }
  case object JSON extends Render {
    override def toString = "json"
  }
}

case class Field(name: String, value: Any)

case class Widget(name: String, render: Render = Render.HTML, props: Map[String, String] = Map()) {
  def toMap: Map[String, String] = Map("name" -> name, "render" -> render.toString) ++ props
  override def toString = toMap.map{ case(k,v) => s"$k:$v"}.mkString("(", ",", ")")

  def withProps(theProps: (String, String)*) = this.copy(props = props ++ theProps)
}

