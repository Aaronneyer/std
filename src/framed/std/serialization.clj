(ns framed.std.serialization
  "Utilities for reading/writing formatted data to disk

   In general:
     reader-like - Anything that can be coerced to an open java.io.Reader
                   using clojure.java.io/reader

     writer-like - Anything that can be coerced to an open java.io.Writer
                   using clojure.java.io/writer

     istream-like - Anything that can be coerced to an open java.io.InputStream
                    using clojure.java.io/input-stream

     ostream-like - Anything that can be coerced to an open java.io.OutputStream
                    using clojure.java.io/output-stream"
  (:require [abracad.avro :as avro]
            [clj-json.core :as json]
            [clojure-csv.core :as csv]
            [clojure.data.fressian :as fressian]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.transit :as transit]
            [taoensso.nippy :as nippy]
            [framed.std.io :as std.io])
  (:import (java.io BufferedReader
                    FileReader
                    EOFException
                    InputStream)
           org.apache.commons.io.IOUtils
           java.util.Arrays
           java.nio.charset.StandardCharsets))

(defn read-csv
  "Return a lazy sequence of rows as vectors of strings
   drop-header - Boolean, whether or not to ignore header row (default: false)"
  ([reader-like]
   (read-csv false reader-like))
  ([drop-header reader-like]
   (let [reader (io/reader reader-like)
         rows (csv/parse-csv reader)]
     (if drop-header
       (drop 1 rows)
       rows))))

(defn write-csv
  "labels - Seq of column labels, ex ['user_id' 'user_email'] (can be empty)
            Note that commas and newlines/carriage returns in labels will be
            replaced with semicolons
   rows - Seq of row data seqs"
  [writer-like labels rows]
  (with-open [writer (io/writer writer-like)]
    (let [sanitize #(string/replace (str %) #"(,|\n|\r)" ";")
          all-rows (if (empty? labels) rows (cons labels rows))]
      (doseq [r all-rows]
        (.write writer (str (string/join "," (map sanitize r)) "\n")))))
  writer-like)

;;

(defn read-json [reader-like]
  (json/parse-string (slurp reader-like)))

(defn write-json
  "Write arbitrary data as JSON to writer-like and return writer-like"
  [writer-like data]
  (spit writer-like (json/generate-string data))
  writer-like)

;;

(def default-transit-encoding :msgpack)

(def valid-transit-encodings #{:msgpack :json})

(defn read-transit
  "Return an IteratorSeq of values from Transit-encoded input"
  ([istream-like]
   (read-transit default-transit-encoding istream-like))
  ([encoding istream-like]
   (let [istream (io/input-stream istream-like)
         reader (transit/reader istream encoding)]
     (transit/read reader))))

(defn write-transit
  "encoding - one of :json, :msgpack"
  ([ostream-like data]
   (write-transit ostream-like default-transit-encoding data))
  ([ostream-like encoding data]
   {:pre [(contains? valid-transit-encodings encoding)]}
   (with-open [ostream (io/output-stream ostream-like)]
     (let [writer (transit/writer ostream encoding)]
       (transit/write writer data)))
   ostream-like))

;;

(defn read-edn [reader-like]
  (edn/read-string (slurp reader-like)))

(defn write-edn
  "Write arbitrary data as EDN to ostream-like and return ostream-like"
  [ostream-like data]
  (spit ostream-like (pr-str data))
  ostream-like)

;;

(defn- read-nippy' [istream]
  (lazy-seq
    (try (cons (nippy/thaw-from-stream! istream)
               (read-nippy' istream))
      (catch EOFException ex
        (do
          (.close istream)
          nil)))))

(defn read-nippy
  "Return a lazy seq of values from Nippy-encoded input"
  [istream-like]
  (let [istream (std.io/data-input-stream istream-like)]
    (read-nippy' istream)))

(defn write-nippy
  "Write a coll of values as Nippy to ostream-like and return ostream-like"
  [ostream-like coll]
  (with-open [ostream (std.io/data-output-stream ostream-like)]
    (doseq [x coll]
      (nippy/freeze-to-stream! ostream x))
    ostream-like))

(deftype NippySeq [file]
  clojure.lang.Seqable
  (seq [x]
    ; Careful of seq semantics! "Double" call necessary here
    (seq (read-nippy file)))

  Object
  (toString [this] (format "file=\"%s\"" file))

  clojure.java.io/Coercions
  (as-file [this] file))

(defn coll->NippySeq
  "Construct a reified view of coll (encoded in Nippy) that is
   is both seqable and accessible as a file on disk

   Ex:
     (def n (coll->NippySeq [1 2 3 4 5]))
     (seq n) ; => '(1 2 3 4 5)
     (clojure.java.io/file n) ; => #<File /tmp/...>"
  [coll]
  (->NippySeq (write-nippy (std.io/tempfile) coll)))

(defn file->NippySeq
  "Construct a NippySeq directly from file-like x"
  [x]
  (->NippySeq (io/file x)))

;;

(defn write-fressian
  "Write a coll of values as Fressian to ostream-like and return ostream-like"
  [ostream-like coll]
  (with-open [out (io/output-stream ostream-like)]
    (let [writer (fressian/create-writer out)]
      (doseq [x coll]
        (fressian/write-object writer x))))
  ostream-like)

(defn- read-fressian' [reader]
  (lazy-seq
    (try (cons (fressian/read-object reader)
               (read-fressian' reader))
      (catch EOFException ex
        (do
          (.close reader)
          nil)))))

(defn read-fressian
  "Return a lazy seq of values from Nippy-encoded input"
  [istream-like]
  (let [reader (->> (io/input-stream istream-like)
                    fressian/create-reader)]
    (read-fressian' reader)))

(deftype FressianSeq [file]
  clojure.lang.Seqable
  (seq [x]
    ; Careful of seq semantics! "Double" call necessary here
    (seq (read-fressian file)))

  Object
  (toString [this] (format "file=\"%s\"" file))

  clojure.java.io/Coercions
  (as-file [this] file))

(defn coll->FressianSeq
  "Construct a reified view of coll (encoded in Fressian) that is
   is both seqable and accessible as a file on disk

   Ex:
     (def f (coll->FressianSeq [1 2 3 4 5]))
     (seq f) ; => '(1 2 3 4 5)
     (clojure.java.io/file f) ; => #<File /tmp/...>"
  [coll]
  (->FressianSeq (write-fressian (std.io/tempfile) coll)))

(defn file->FressianSeq
  "Construct a FressianSeq directly from file-like x"
  [x]
  (->FressianSeq (io/file x)))

;;

(def avro-schema
  "Alias over Abracad for specifying an Avro RecordSchema
   See https://github.com/damballa/abracad for format documentation

   Ex:
   (def schema
     (avro-schema {:type \"record\"
                   :name \"User\"
                   :fields [{:name \"age\" :type \"long\"}
                            {:name \"email\" :type \"string\"}]})"
  avro/parse-schema)

(defn write-avro
  "Write coll of records each conforming to schema to file-like x
   Ex:

   (def schema
     (avro-schema {:type \"record\"
                   :name \"User\"
                   :fields [{:name \"age\" :type \"long\"}
                            {:name \"email\" :type \"string\"}]})
   (write-avro \"test.avro\" schema [{:age 27 :email \"foo@example.com\"}
                                     {:age 32 :email \"bar@example.com\"}])
   ; => #<File test.avro>"
  [x schema coll]
  (avro/mspit schema x coll)
  (io/file x))

(defn- read-avro' [adf]
  (lazy-seq
    (if (.hasNext adf)
      (cons (.next adf) (read-avro' adf))
      (do (.close adf)
          nil))))

(defn read-avro
  "TODO: if passed an InputStream, will consume entire stream
   in-memory (arbitrarily large files are OK)"
  [x]
  (let [source
        (if (instance? InputStream x)
          (let [bs (IOUtils/toByteArray x)] ; Consumes entire stream!
            (.close x)
            bs)
          (.getPath (io/file x)))]
    (->> source
         avro/data-file-reader
         read-avro')))

(def encode-avro
  ":: schema -> record -> bytes"
  avro/binary-encoded)

(def decode-avro
  ":: schema -> bytes -> record"
  avro/decode)
