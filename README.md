# LazyDrawable - 'on-demand' drawables loading for android

developed for wATLlib, but useful in other cases

### Core Classes:
##### su.whs.wlazydrawable.LazyDrawable
    - abstract class. 
    - you must override methods:
    ```java
    public void onVisibilityChanged(boolean visible) {
        // this method invoked by wATL layout calculation, when drawable becomes visible/invisible on canvas
    }
    protected Drawable readDrawable() {
        // !background thread
        // this method called after load() call, or after first draw() call
    }
    
    protected void onLoadingError() {
        // handle loading error here
    }
    ```
    
##### su.whs.wlazydrawable.PreviewDrawable
    - abstract class with support two sources for drawable - PREVIEW and FULL
    - you must override methods:
    ```java
    protected Drawable getPreviewDrawable() {
        // similar to LazyDrawable.readDrawable() - must returns preview version of drawable
    }
    
    protected Drawable getFullDrawable() {
        // called from background thread after loadFullDrawable() invoked
        // must returns full version of drawable
    }
    ```
    
##### su.whs.wlazydrawable.RemoteDrawable
    - abstract class, inherited from PreviewDrawable
    - constructed with previewUrl and fullUrl
    - you must override methods
    ```java
    protected void onSizeDecoded(int width, int height) {
        // !background thread
        // called from 'decode stream stage'
        // width and height contains decoded image size
    }
    
    protected InputStream getInputStream(String url) throws IOException {
        // !background thread
        // must returns InputStream for given url (previewUrl or fullUrl, pessed with constructor)
    }
    ```


# LICENSE: 
    (http://www.apache.org/licenses/LICENSE-2.0 "APACHE-2.0")
    
# USAGE:

add to module _build.gradle_ 'dependencies' section
    compile 'su.whs:wlazydrawable:1.0.2' 

