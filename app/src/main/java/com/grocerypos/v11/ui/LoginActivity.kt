package com.grocerypos.v11.ui
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.launch
class LoginActivity:AppCompatActivity(){
    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        val l=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,30,30,30)}
        val u=EditText(this).apply{hint="Username"}
        val p=EditText(this).apply{hint="Password";inputType=0x81}
        val btn=Button(this).apply{text="LOGIN"}
        l.addView(TextView(this).apply{text="Grocery POS V13";textSize=26f})
        l.addView(u);l.addView(p);l.addView(btn);setContentView(l)
        btn.setOnClickListener{
            lifecycleScope.launch{
                val user=PosDatabase.get(this@LoginActivity).userDao().find(u.text.toString())
                if(user!=null && user.passwordHash==p.text.toString()){
                    Toast.makeText(this@LoginActivity,"Welcome ${user.displayName}",Toast.LENGTH_SHORT).show()
                    finish()
                }else Toast.makeText(this@LoginActivity,"Invalid login",Toast.LENGTH_SHORT).show()
            }
        }
    }
}
