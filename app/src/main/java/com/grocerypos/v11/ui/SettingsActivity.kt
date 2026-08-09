package com.grocerypos.v11.ui
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
class SettingsActivity:AppCompatActivity(){
 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  val l=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
  l.addView(TextView(this).apply{text="SETTINGS";textSize=24f})
  listOf("Shop Name","Phone","Address","Receipt Footer","Currency","Tax %").forEach{
   l.addView(EditText(this).apply{hint=it})
  }
  l.addView(Button(this).apply{text="SAVE SETTINGS"})
  setContentView(l)
 }
}
