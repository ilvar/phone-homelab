package dev.phoneport.core

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.*

interface PortainerApi {
    @POST("api/auth") fun auth(@Body body: RequestBody): Call<ResponseBody>
    @GET("api/endpoints") fun endpoints(): Call<ResponseBody>
    // Portainer parses environment creation as multipart because the remote forms carry TLS files.
    @Multipart @POST("api/endpoints") fun createEndpoint(@Part("Name") name: RequestBody, @Part("EndpointCreationType") type: RequestBody): Call<ResponseBody>
    @POST("api/endpoints/{id}/docker/containers/create") fun createContainer(@Path("id") endpoint: Int, @Query("name") name: String, @Body body: RequestBody): Call<ResponseBody>
    @POST("api/endpoints/{id}/docker/containers/{cid}/{action}") fun containerAction(@Path("id") endpoint: Int, @Path("cid") container: String, @Path("action") action: String): Call<ResponseBody>
    @DELETE("api/endpoints/{id}/docker/containers/{cid}") fun removeContainer(@Path("id") endpoint: Int, @Path("cid") container: String): Call<ResponseBody>
    @Streaming @POST("api/endpoints/{id}/docker/images/create") fun pull(@Path("id") endpoint: Int, @Query("fromImage") image: String, @Query("tag") tag: String): Call<ResponseBody>
    @GET("api/endpoints/{id}/docker/containers/json?all=1") fun containers(@Path("id") endpoint: Int): Call<ResponseBody>
    @GET("api/stacks") fun stacks(): Call<ResponseBody>
    @POST("api/stacks/create/standalone/string") fun createStack(@Query("endpointId") endpoint: Int, @Body body: RequestBody): Call<ResponseBody>
    @POST("api/stacks/{id}/{action}") fun stackAction(@Path("id") id: Int, @Path("action") action: String, @Query("endpointId") endpoint: Int): Call<ResponseBody>
    @DELETE("api/stacks/{id}") fun removeStack(@Path("id") id: Int, @Query("endpointId") endpoint: Int): Call<ResponseBody>
    @Streaming @GET("api/endpoints/{id}/docker/containers/{cid}/logs?stdout=1&stderr=1&tail=500&follow=1") fun logs(@Path("id") endpoint: Int, @Path("cid") container: String): Call<ResponseBody>
    @GET("api/endpoints/{id}/docker/containers/{cid}/json") fun inspect(@Path("id") endpoint: Int, @Path("cid") container: String): Call<ResponseBody>
}
