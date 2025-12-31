package com.aiphotomaster.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aiphotomaster.databinding.ActivityMainBinding

/**
 * 主界面 Activity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // TODO: 初始化 UI 组件
    }
}
