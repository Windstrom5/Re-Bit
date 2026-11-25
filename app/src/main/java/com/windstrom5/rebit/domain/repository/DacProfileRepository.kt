package com.windstrom5.rebit.domain.repository

import com.windstrom5.rebit.domain.model.DacProfile
import kotlinx.coroutines.flow.Flow

interface DacProfileRepository {
    fun observeProfiles(): Flow<List<DacProfile>>
    suspend fun saveProfile(profile: DacProfile): Long
    suspend fun findByUsbIds(vendorId: Int, productId: Int): DacProfile?
    suspend fun getProfile(id: Long): DacProfile?
}
